package run.cosy.ldp.fs

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{scaladsl, ActorRef, Behavior}
import akka.http.scaladsl.common.StrictForm.FileData
import akka.http.scaladsl.model.MediaTypes.{`text/plain`, `text/x-java-source`}
import akka.http.scaladsl.model.ContentTypes.{`text/html(UTF-8)`, `text/plain(UTF-8)`}
import akka.http.scaladsl.model.HttpCharsets.`UTF-8`
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{`Content-Type`, `WWW-Authenticate`, Accept, Allow, ETag, HttpChallenge, Link, LinkParams, LinkValue, Location}
import akka.http.scaladsl.model.StatusCodes.{Conflict, Created, Gone, InternalServerError, MethodNotAllowed, NoContent}
import akka.http.scaladsl.server.ContentNegotiator.Alternative.ContentType
import akka.stream.{IOResult, Materializer}
import akka.stream.scaladsl.FileIO
import run.cosy.http.FileExtensions
import run.cosy.http.auth.{KeyIdAgent, WebServerAgent}
import run.cosy.ldp.ResourceRegistry
import run.cosy.ldp.Messages._
import run.cosy.ldp.fs.Resource.{connegNamesFor, extension, headersFor, StateSaved}
import run.cosy.http.auth.KeyIdAgent
import run.cosy.ldp.fs.BasicContainer.{LinkHeaders, PostCreation}

import java.nio.file.{CopyOption, Files, Path => FPath}
import java.nio.file.attribute.{BasicFileAttributes, FileAttribute}
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.Principal
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

//import akka.http.scaladsl.model.headers.{RequestClientCertificate, `Tls-Session-Info`}

object Resource {

	import akka.stream.Materializer
	import BasicContainer.ldp

	import java.nio.file.Path
	import scala.concurrent.ExecutionContext

	type AcceptMsg = WannaDo | Do | PostCreation | StateSaved
	
	//guard could be a pool?
	//var guard: ActorRef = _

//  @throws[Exception](classOf[Exception])
//  def preStart() = {
//    log.info(s"starting guard for $ldprUri ")
//    guard = context.actorOf(Props(new Guard(ldprUri,List())))
//  }

	case class StateSaved(at: Path, replyTo: ActorRef[HttpResponse])

	def mediaType(path: FPath): MediaType = FileExtensions.forExtension(extension(path))

	import run.cosy.RDF._

	val AllowHeader =
		import HttpMethods._
		Allow(GET,PUT,DELETE,HEAD,OPTIONS) //add POST for Solid Resources

	def extension(path: FPath) =
		val file = path.getFileName.toString
		var n = file.lastIndexOf('.')
		if n > 0 then file.substring(n + 1) else ""

	def headersFor(att: BasicFileAttributes): List[HttpHeader] =
		eTag(att)::lastModified(att)::Nil

	def eTag(att: BasicFileAttributes): ETag =
		import att._
		//todo: rather than the file key, which contains an inode number, one could just use the version name
		ETag(s"${lastModifiedTime.toMillis}_${size}_${fileKey.hashCode()}")

	def lastModified(att: BasicFileAttributes): HttpHeader =
		import akka.http.scaladsl.model.headers.`Last-Modified`
		`Last-Modified`(DateTime(att.lastModifiedTime().toMillis))

	def apply(rUri: Uri, linkName: FPath, name: String): Behavior[AcceptMsg] =
		Behaviors.setup[AcceptMsg] { (context: ActorContext[AcceptMsg]) =>
			//val exists = Files.exists(root)
//			val registry = ResourceRegistry(context.system)
//			registry.addActorRef(rUri.path, context.self)
//			context.log.info("started LDPR actor at " + rUri.path)
			new Resource(rUri, linkName, context).behavior
		}

	/** todo: language versions, etc...
	 * */
	def connegNamesFor(name: String, ct: `Content-Type`): List[String] =
		ct.contentType.mediaType.fileExtensions.map(name + "." + _)

	object Version {
		def unapply(ver: String): Option[Int] = ver.toIntOption
	}
}

import run.cosy.ldp.fs.Resource.AcceptMsg
import run.cosy.ldp.fs.BasicContainer.PostCreation

/**
 * LDPR Actor extended with Access Control.
 * The LDPR Actor should keep track of states for a resource, such as previous versions,
 * and variants (e.g. human translations, or just different formats for the same content).
 * The LDPR actor deals with creation and update to resources.
 *
 * A Resource can react to
 *  - GET and do content negotiation
 *  - PUT for changes
 *  - PATCH and QUERY potentially
 *
 * An LDPR actor should garbage collect after inactive time, to reduce memory useage.
 *
 * This implementation makes a number of arbitrary decisions.
 * 1. Resources are symlinks pointing to default representations
 * 2.
 */
class Resource(uri: Uri, linkPath: FPath, context: ActorContext[AcceptMsg]) {
	import Resource.mediaType
	import context.log
	val name = uri.path.reverse.head.toString

	def LDPR = LinkValue(run.cosy.ldp.fs.BasicContainer.ldpr,LinkParams.rel("type"),LinkParams.anchor(uri))
	def LatestVersion = LinkValue(uri,LinkParams.rel("latest-version"))

	def behavior: Behaviors.Receive[AcceptMsg] = {
		val linkTo = linkPath.resolveSibling(Files.readSymbolicLink(linkPath))
		if linkTo.getFileName.toString.endsWith(".archive") then GoneBehavior
		//todo: can we avoid this request to the FSystem?
		else if !Files.exists(linkTo) then justCreatedBehavior
		else try {
			val version = linkTo.getFileName.toString.split('.').toSeq match {
				case Seq(name,version,extension) => version.toIntOption.getOrElse(0)
				case Seq(name,extension) => 0
			}
			new VersionsInfo(version, linkTo).NormalBehavior
		} catch {
			case e: java.nio.file.NoSuchFileException => justCreatedBehavior
			//case e: java.io.IOException => ???
				//this would be a rare type of exception where ???
		}
	}

	/**
	 * All transformations come in two phases (it seems)
	 *   1. creating a new name (to save data to)
	 *   1. filling in the data by transformation from the old resource to the new one.
	 *      the transformation can be just to copy all the data, or to PATCH the old resource,
	 *      or DELETE the last one giving a new end of state.
	 *
	 *  Here we start seeing the beginning of such generality, but as we don't have PATCH we only
	 *  see the creation of a new version - either the initial version, or the next version.
	 *  The above also works with delete, if we think of it as moving everything to an invisible archive
	 *  directory.
	 **
	 * @param cr the PostCreation message (still looking for a better name)
	 */
	def BuildContent(cr: PostCreation): Unit =
		val PostCreation(linkToPath, Do(_,req,replyTo)) = cr
		log.info(
			s"""received POST request with headers ${req.headers} and CT=${req.entity.contentType}
				|Saving to: $linkToPath""".stripMargin)
		import cr.given
		val f: Future[IOResult] = req.entity.dataBytes.runWith(FileIO.toPath(linkToPath))
		context.pipeToSelf(f){
			case Success(IOResult(count, _)) => StateSaved(linkToPath, replyTo)
			case Failure(e) =>
				log.warn(s"Unable to prcess request $cr. Deleting $linkToPath")
				// actually one may want to allow the client to continue from where it left off
				// but until then we delete the broken resource immediately
				Files.deleteIfExists(linkToPath)
				StateSaved(null,replyTo)
		}

	def justCreatedBehavior = 	Behaviors.receiveMessage[AcceptMsg] { (msg: AcceptMsg) =>
		msg match
			case cr : PostCreation =>
				BuildContent(cr)
				Behaviors.same
			case StateSaved(null,replyTo) =>
				//If we can't save the body of a POST then the POST fails
				// todo: the parent should also be notified... (which it will with stopped below, but enough?)
				Files.delete(linkPath)
				// is there a more specific error to be had?
				replyTo ! HttpResponse(StatusCodes.InternalServerError,
					entity=HttpEntity("upload unsucessful")
				)
				Behaviors.stopped
			case StateSaved(pos,replyTo) =>
				//todo: we may only want to check that the symlinks are correct
				import java.nio.file.StandardCopyOption.ATOMIC_MOVE
				import Resource._
				val tmpSymLinkPath = Files.createSymbolicLink(
					linkPath.resolveSibling(pos.getFileName.toString+".tmp"), pos.getFileName
				)
				Files.move(tmpSymLinkPath,linkPath,ATOMIC_MOVE)
				val att = Files.readAttributes(pos, classOf[BasicFileAttributes])
				replyTo ! HttpResponse(Created,
					Location(uri)::Link(LDPR::Nil)::AllowHeader::headersFor(att),
					HttpEntity(`text/plain(UTF-8)`, s"uploaded ${att.size()} bytes")
				)
				behavior
			case reqMsg : ReqMsg =>
				//todo: here we could change the behavior to one where requests are stashed until the upload
				// is finished, or where they are returned with a "please wait" response...
				reqMsg.replyTo ! HttpResponse(Conflict,entity=HttpEntity("the resource is not yet completely uploaded. Try again later."))
				Behaviors.same
	}

	// todo: Gone behavior may also stop itself earlier
	def GoneBehavior: Behaviors.Receive[AcceptMsg] =
		Behaviors.receiveMessage[AcceptMsg] { (msg: AcceptMsg) =>
			msg match
				case cmd: ReqMsg => cmd.replyTo ! HttpResponse(Gone)
				case PostCreation(_, Do(_, req, replyTo)) =>
					log.warn(s"received Create on resource <$uri> at <$linkPath> that has been deleted! " +
						s"Message should not reach this point.")
					replyTo ! HttpResponse(InternalServerError,entity=HttpEntity("please contact admin"))
				case saved: StateSaved =>
					log.warn(s"received a state saved on source <$uri> at <$linkPath> that has been deleted!")
			Behaviors.same
		}

	/**
	 * Aiming for [[https://tools.ietf.org/html/rfc7089 RFC7089 Memento]] perhaps?
	 * See work on a [[http://timetravel.mementoweb.org/guide/api/ memento ontology]] see
	 * [[https://groups.google.com/g/memento-dev/c/xNHUXDxPxaQ/m/DHG26vzpBAAJ 2017 discussion]] and
	 * [[https://github.com/fcrepo/fcrepo-specification/issues/245#issuecomment-338482569 issue]].
	 *
	 * How much of memento can we implement quickly to get going, in order to test some other aspects such
	 * as access control? This looks like a good starting point:
	 * [[https://tools.ietf.org/html/rfc5829 RFC 5829 Link Relation Types for Simple Version Navigation between Web Resources]].
	 *
	 * We start by having one root variant for a resource, which can give rise to other
	 * variants built from it automatically (e.g. Turtle variant can give rise to RDF/XML,
	 * JSON/LD, etc...), but we keep the history of the principal variant.
	 * todo: for now, we assume that we keep the same mime types throughout the life of the resource.
	 *    allowing complexity here, tends to make one want to open the resource into a container like structure,
	 *    as it would allow one to get all the resources together.
	 * So we start like this:
	 *
	 *  1. Container receives a POST (or PUT) for a new resource
	 *   i. `<card> -> <card>` create loop symlink (or we would need to point `<card>` to something that does not exist, or that is only partially uploaded, ...)
	 *   i. create file `<card.0.ttl>` and pour in the content from the POST or PUT
	 *   i. when finished relink `<card> -> <card.0.tll>`
	 *
	 *  1. UPDATING WITH PUT/PATCH follows a similar procedure
	 *   i. `card -> card.v0.ttl`  starting point
	 *   i. create `<card.1.ttl>` and save new data to that file
	 *   i. when finished relink `<card> -> <card.1.ttl>`
	 *   i. `<card.v1.ttl>` can have a `Link: ` header pointing to previous version `<card.0.ttl>`.
	 *
	 * Later we may want to allow upload of say humanly translated variants of some content.
	 *
	 * Note: there are many ways to do this, and there is no reason one could not have different
	 * implementations. Some different ideas are:
	 *  - Metadata in files
	 *    + one can place metadata into Attributes (Java supports that)
	 *    + or one can place metadata into a conventionally named RDF file
	 *     (with info of how the different versions are connected)
	 *  - Place variants in a directory to speed up search for variants
	 *  - specialised versioning File Systems
	 *  - implement this over CVS or git, ...
	 *
	 *  Here we will assume that the content of the dir is following the convention
	 *  content.$num.extension
	 *
	 * @param lastVersion max version of this resource. The one the link is pointing to
	 * @param linkTo the path of the latest reference version
	 * @param attr basic file attributes of the latest version (do we really need this?)
	 * @param ct: content type of all the default versions -- very limiting to start off with.
	 */
	class VersionsInfo(lastVersion: Int, linkTo: FPath) {
		import akka.http.scaladsl.model.HttpMethods.{GET, HEAD, POST, DELETE, OPTIONS, PUT}
		import akka.http.scaladsl.model.StatusCodes.{Created, InternalServerError, Gone}
		import Resource.{lastModified,eTag,AllowHeader}
		var PUTstarted = false

		def vName(v: Int): String = name+"."+v
		def versionUrl(v: Int): Uri =
			val vp = uri.path.reverse.tail.reverse ++ akka.http.scaladsl.model.Uri.Path(vName(v))
			uri.withPath(vp)
		def versionFPath(v: Int, ext: String): FPath = linkPath.resolveSibling(vName(v)+"."+ext)

		def PrevVersion(v: Int): List[LinkValue] =
			if v > 0 then LinkValue(versionUrl(v-1),LinkParams.rel("predecessor-version"))::Nil else Nil
		def NextVersion(v: Int): List[LinkValue] =
			if v < lastVersion then LinkValue(versionUrl(v+1),LinkParams.rel("successor-version"))::Nil else Nil
		def VersionLinks(v: Int): List[LinkValue] = LatestVersion::(PrevVersion(v):::NextVersion(v))

		def Authorize(wd: WannaDo) =
			if true || List(WebServerAgent,KeyIdAgent("/user/key#")).exists(_ == wd.from) then
				import wd.{given, *}
				context.self ! Do(from, req, replyTo)
			else wd.replyTo ! HttpResponse(StatusCodes.Unauthorized,
				Seq(`WWW-Authenticate`(HttpChallenge("Signature",s"$uri")))
			)
			Behaviors.same

		def NormalBehavior: Behaviors.Receive[AcceptMsg] =
			import Resource._
			Behaviors.receiveMessage[AcceptMsg] { (msg: AcceptMsg) =>
				msg match
				case wd: WannaDo => Authorize(wd)
					Behaviors.same
				case d@Do(_, req, replyTo) =>
					req.method match
					case GET | HEAD =>
						replyTo ! Get(req)
						Behaviors.same
					case OPTIONS =>
						replyTo ! HttpResponse ( //todo, add more
							NoContent, AllowHeader :: Nil
						)
						Behaviors.same
					case PUT =>
						import d.given
						Put(req,replyTo)
						Behaviors.same
					case DELETE => Delete(req, replyTo)
					case m =>
						replyTo ! HttpResponse(MethodNotAllowed,
							AllowHeader::Nil,HttpEntity(s"method $m not supported")
						)
						Behaviors.same
				case pc: PostCreation =>
					//the PostCreation message is sent from Put(...), but it is immediately acted on
					// and so does not come through here. Still if it comes through here it would
					// call this method. Should it come through here?
					BuildContent(pc)
					 //todo: cleanup, the PUT above should create a name, and then send the PostCreation message
					Behaviors.same
				case StateSaved(null,replyTo) =>
					PUTstarted = false
					//the upload failed, but we can return info about the current state
					val att = Files.readAttributes(linkTo, classOf[BasicFileAttributes])
					replyTo ! HttpResponse(StatusCodes.InternalServerError,
						Location(uri)::AllowHeader::Link(LDPR::VersionLinks(lastVersion))::headersFor(att),
						entity=HttpEntity("PUT unsucessful")
					)
					Behaviors.same
				case StateSaved(fpath,replyTo) =>
					import java.nio.file.StandardCopyOption.ATOMIC_MOVE
					val tmpSymLinkPath = Files.createSymbolicLink(
						linkPath.resolveSibling(linkPath.getFileName.toString+".tmp"),
							fpath.getFileName)
					Files.move(tmpSymLinkPath,linkPath,ATOMIC_MOVE)
					//todo: state saved should just return the VersionInfo.
					val att = Files.readAttributes(fpath, classOf[BasicFileAttributes])
					replyTo ! HttpResponse(akka.http.scaladsl.model.StatusCodes.OK,
							Location(uri)::AllowHeader::Link(LDPR::VersionLinks(lastVersion+1))::headersFor(att),
							HttpEntity(`text/plain(UTF-8)`, s"uploaded ${att.size()} bytes")
					)
					new VersionsInfo(lastVersion+1,fpath).NormalBehavior
			}
		end NormalBehavior

		/**
		 * Two ways to count versions both have problems
		 *  1. we start the process of building the new resource now, and when finished change
		 *     the name. But what happens then if another PUT requests comes in-between?
		 *     - If we number each version with an int, we risk overwriting an existing version
		 *     - If create Unique names then we don't have a simple mechansims to find previous
		 *      versions
		 *  1. If we immediately increase the version counter by 1
		 * 	- then if a PUT fails, we could end up with gaps in our virtual linked history
		 *
       *  possible answers:
		 *    a. we save versions to a directory, where it is easy to get an oversight over
		 *      all versions of a resource, and we can use the creation metadata of the FS to
		 *      work out what the versions are. We can even use numbering with missing parts, because
		 *      getting a full list of the versions and reconstituting the numbers is easy given
		 *      that they are confined in a directory. 
		 *    a. we need to keep a resource (attribute or file) of the versions and their relations
		 *    a. we don't allow another PUT before the previous one is finished...
		 *
		 *  The last one is not the most elegant perhaps, but will work. The others options
		 *  can be explored in other implementations.  (Note: Post as Append on LDPRs that accept
		 *  it adds an extra twist, not taken into account here)
		 */
		private def Put(req: HttpRequest, replyTo: ActorRef[HttpResponse])(
			using ec: ExecutionContext, mat: Materializer
		): Unit = {
			//1. we filter out all unaceptable requests, and return error responses to those
			if PUTstarted then
				replyTo !  HttpResponse(
					StatusCodes.Conflict, Seq(),
					HttpEntity("PUT already ongoing. Please try later"))
				return ()

			import headers.{`If-Match`,`If-Unmodified-Since`,EntityTag}
			val imh = req.headers[`If-Match`]
			val ium = req.headers[`If-Unmodified-Since`]
			lazy val att = Files.readAttributes(linkTo, classOf[BasicFileAttributes])

			if imh.isEmpty && ium.isEmpty then
				replyTo ! HttpResponse(StatusCodes.PreconditionRequired,
					Location(uri) :: AllowHeader :: Link(LDPR :: VersionLinks(lastVersion)) :: headersFor(att),
					HttpEntity("LDP Servers requires valid `If-Match` or `If-Unmodified-Since` headers for a PUT")
				)
				return ()

			val precond1 = imh.exists { case `If-Match`(range) =>
				val et = eTag(att)
				EntityTag.matchesRange(et.etag, range, false)
			}
			lazy val precond2 =
				req.headers[`If-Unmodified-Since`].map { case `If-Unmodified-Since`(dt) =>
					dt.clicks >= att.lastModifiedTime().toMillis
				}
			if !(precond1 || (precond2.size > 0 && precond2.forall(_ == true))) then
				replyTo ! HttpResponse(StatusCodes.PreconditionFailed,
					Location(uri) :: AllowHeader :: Link(LDPR :: VersionLinks(lastVersion)) :: headersFor(att),
					HttpEntity("LDP Servers requires valid `If-Match` or `If-Unmodified-Since` headers for a PUT")
				)
				return ()

			//2. We save data to storage, which if successful will lead to a new version message to be sent
			//todo: we need to check that the extension fits the previous mime types
			//     this is definitively clumsy.
			linkTo.getFileName.toString.split('.').toSeq match
				case Seq(name, ver, ext) if req.entity.contentType.mediaType.fileExtensions.contains(ext) =>
					val linkToPath: FPath = versionFPath(lastVersion, ext)
					PUTstarted = true
					BuildContent(PostCreation(linkToPath, Do(WebServerAgent, req, replyTo)))
					//todo: here we should change the behavior to one where requests are stashed until the upload
					// is finished, or where they are returned with a "please wait" response...
				case _ => replyTo ! HttpResponse(StatusCodes.Conflict,
						Seq(),
						HttpEntity("At present we can only accept a PUT with the same " +
						"Media Type as previously sent. Which is " + extension(linkTo))
				)
		}



		private def GetVersionResponse(path: FPath, ver: Int, mt: MediaType): HttpResponse =
			try {
				//todo: Akka has a MediaTypeNegotiator
				val attr = Files.readAttributes(path, classOf[BasicFileAttributes])
				HttpResponse(
					StatusCodes.OK,
					lastModified(attr)::eTag(attr)::AllowHeader::Link(LDPR::VersionLinks(ver))::Nil,
					HttpEntity.Default(
						akka.http.scaladsl.model.ContentType(mt, () => HttpCharsets.`UTF-8`),
						path.toFile.length(),
						FileIO.fromPath(path)
					))
			} catch {
				case e: Exception => HttpResponse(
						StatusCodes.NotFound,
						entity = HttpEntity(`text/html(UTF-8)`,
							s"could not find attributes for content: " + e.toString
						))
			}



		private def Get(req: HttpRequest): HttpResponse =
			val mt: MediaType = mediaType(linkTo)
			val acceptHdrs = req.headers[Accept]
			if acceptHdrs.exists{ acc => acc.mediaRanges.exists(mr => mr.matches(mt)) } then {
				import Resource.Version
				req.uri.path.reverse.head.toString.split('.').toSeq match {
					case p @ Seq(prefix,Version(num),ext) if mt.fileExtensions.contains(ext) =>
						GetVersionResponse(linkPath.resolveSibling(p.mkString(".")),num,mt)
					case Seq(prefix,Version(num)) if num >= 0 && num <= lastVersion =>
						//the request is for a version, but mime left open
						GetVersionResponse(linkPath.resolveSibling(s"$prefix.$num.${mt.fileExtensions.head}"),num,mt)
					case Seq(name) => GetVersionResponse(linkTo,lastVersion,mt)
					case _ => HttpResponse(
						StatusCodes.NotFound,
						entity=HttpEntity(s"could not find resource for <${req.uri}> with "+req.headers)
					)
				}
			} else
				import akka.http.scaladsl.model.ContentTypes.`text/html(UTF-8)`
				HttpResponse(
					StatusCodes.UnsupportedMediaType, 
					entity=HttpEntity(`text/html(UTF-8)`,
						s"requested content Types where $acceptHdrs but we only have $mt"
					))
		end Get

		/**
		 * Specified in:
		 *  - [[https://tools.ietf.org/html/rfc7231#section-4.3.5 4.3.5 DELETE]] of HTTP 1.1 RFC 7231 
		 *  - [[https://www.w3.org/TR/ldp/#ldpc-HTTP_DELETE 5.2.5 HTTP DELETE]] of LDP spec
		 *  - [[https://solidproject.org/TR/protocol#deleting-resources in 5.4 Deleting Resources]] of Solid Protocol
		 *  
		 * Creates a "file.archive" directory, moves the link into it and points the link to the archive.
		 * Then it can return a response. And the done behavior is started after cleanup.
		 *  
		 * Should also delete Auxilliary resources (which at present just include the linked-to document)
		 * When done the actor must shutdown and send a deleted notification to the parent, so that it
		 * can remove the link from its cache.
		 * 
		 * what should one do if a delete fails on some of the resources?
		 * Best to create an archive directory and move all files there, so that they are no longer visible, 
		 * and can be cleaned away later.
		 *
		 * 
		 * @param req the DELETE Request
		 * @param replyTo
		 * @return
		 */
		def Delete(req: HttpRequest, replyTo: ActorRef[HttpResponse]): Behavior[AcceptMsg] =
			import java.nio.file.StandardCopyOption

			import java.nio.file.LinkOption.NOFOLLOW_LINKS
			import akka.http.scaladsl.model.StatusCodes.{Conflict, NoContent}

			import java.io.IOException
			import java.nio.file.attribute.BasicFileAttributes
			import java.nio.file.{DirectoryNotEmptyException, FileAlreadyExistsException, NoSuchFileException, NotLinkException,AtomicMoveNotSupportedException}

			val javaMime = `text/x-java-source`.withCharset(`UTF-8`)
			val linkTo: FPath = try {
				val lp = Files.readSymbolicLink(linkPath)
				linkPath.resolveSibling(lp)
			} catch {
				case e: UnsupportedOperationException => 
					log.error("Operating Systems does not support links.",e)
					replyTo ! HttpResponse(InternalServerError, entity=HttpEntity(`text/plain(UTF-8)`,"Server error"))
					//todo: one could avoid closing the whole system and instead shut down the root solid actor (as other
					// parts of the actor system could continue working
					context.system.terminate()
					return Behaviors.stopped
				case e: NotLinkException => 
					log.warn(s"type of <$linkPath> changed since actor started",e)
					replyTo ! HttpResponse(Conflict, entity=HttpEntity(`text/plain(UTF-8)`,
						"Resource seems to have changed type"))
					return Behaviors.stopped
				case e : SecurityException => 
					log.error(s"Could not read symbolic link <$linkPath> on DELETE request", e)
					//todo: returning an InternalServer error as this is not expected behavior
					replyTo ! HttpResponse(InternalServerError, entity=HttpEntity(`text/plain(UTF-8)`, 
						"There was a problem deleting resource on server. Contact Admin."))
					return Behaviors.stopped
				case e: IOException => 
					log.error(s"Could not read symbolic link <$linkPath> on DELETE request. " +
						s"Will continue delete attempt",e)
					linkPath
			}
			val archive = try {
				Files.createDirectory(archiveDir)
			} catch {
				case e: UnsupportedOperationException =>
					log.error("Operating Systems does not support creation of directory with no(!) attributes!" +
						" This should never happen", e)
					replyTo ! HttpResponse(InternalServerError, entity=HttpEntity(`text/plain(UTF-8)`,"Server error"))
					//todo: one could avoid closing the whole system and instead shut down the root solid actor (as other
					// parts of the actor system could continue working
					context.system.terminate()
					return Behaviors.stopped
				case e: FileAlreadyExistsException =>
					log.warn(s"Archive directory <$archiveDir> allready exists. " +
						s"This should not have happened. Logic error in program suspected. " +
						s"Or user created archive dir by hand. " +
						s"Will continue by trying to save to existing archive. Things could go wrong.", e)
					archiveDir	
				case e: SecurityException => 
					log.error(s"Exception trying to create archive directory. " +
						s"JVM is potentially badly configured. ", e)
					replyTo ! HttpResponse(InternalServerError, entity=HttpEntity(`text/plain(UTF-8)`,
						"Server error. Potentially badly configured."))
					return Behaviors.stopped
				case e: IOException =>  
					log.error(s"Could not create archive dir <$archiveDir> for DELETE", e)
					replyTo ! HttpResponse(InternalServerError, entity=HttpEntity(`text/plain(UTF-8)`, "could not delete, contact admin"))
					return Behaviors.stopped
			}
			
			try { //move link to archive. At the minimum this will result in a delete
				Files.move(linkPath, archive.resolve(linkPath.getFileName))
			} catch {
				case e: UnsupportedOperationException => // we don't even have a symlink. Problem!
					log.error(s"tried move <$linkPath> to <$archive>. " +
						s"But OS does not support an attribute?. JVM badly configured. ",e)
					replyTo ! HttpResponse(InternalServerError,
						entity=HttpEntity(`text/plain(UTF-8)`,"Server badly configured. Contact admin."))
					return Behaviors.stopped
				case e: DirectoryNotEmptyException =>
					log.error(s"tried move link <$linkPath> to <$archive>. " +
						s" But it turns out the moved object is a non empty directory!",e)
					replyTo ! HttpResponse(Conflict, entity=HttpEntity(`text/plain(UTF-8)`,
						"Coherence problem with resources. Try again."))
					return Behaviors.stopped
				case e: AtomicMoveNotSupportedException => 
					log.error(s"Message should not appear as Atomic Move was not requested. Corrupt JVM?",e)
					replyTo ! HttpResponse(InternalServerError,
						entity=HttpEntity(`text/plain(UTF-8)`,"Server programming or installation error"))
					return Behaviors.stopped
				case e: SecurityException =>
					log.error(s"Security Exception trying move link <$linkPath> to archive.",e)
					replyTo ! HttpResponse(InternalServerError,
						entity=HttpEntity(`text/plain(UTF-8)`,"Server programming or installation error"))
					return Behaviors.stopped
				case e: IOException => 
					log.error(s"IOException trying move link <$linkPath> to archive.",e)
					replyTo ! HttpResponse(InternalServerError,
						entity=HttpEntity(`text/plain(UTF-8)`,"Server programming or installation error"))
					return Behaviors.stopped
				case e: FileAlreadyExistsException => 
					log.warn(s"trying to move <$linkPath> to <$archive> but a file with that name already exists. " +
						s"Will continue with DELETE operation.")
					try {
						Files.delete(linkPath)
					} catch {
						case e: NoSuchFileException => 
							log.warn(s"Trying to delete <$linkPath> link but it no longer exists. Will continue DELETE operation")
						case e: DirectoryNotEmptyException =>
							log.warn(s"Attempting to delete <$linkPath> link but it suddenly " +
								s" seems to have turned into a non empty directory! ", e)
							replyTo ! HttpResponse(Conflict,
								entity=HttpEntity(`text/plain(UTF-8)`,"Coherence problem with resources. Try again."))
							return Behaviors.stopped
						case e: SecurityException =>
							log.error(s"trying to delete <$linkPath> caused a security exception. " +
								s"JVM rights not properly configured.",e)
							replyTo ! HttpResponse(InternalServerError,
								entity=HttpEntity(`text/plain(UTF-8)`,"Server badly configured. Contact admin."))
							return Behaviors.stopped
						case e : IOException =>
							log.error(s"trying to delete <$linkPath> link cause IO Error." +
								s"File System problem.",e)
							replyTo ! HttpResponse(InternalServerError,
								entity=HttpEntity(`text/plain(UTF-8)`,"Problem on server. Contact admin."))
							return Behaviors.stopped
					}
			}
			try {
				Files.createSymbolicLink(linkPath,linkPath.getFileName)
			} catch {
				case e => 
					log.warn(s"could not create a self link <$linkPath> -> <$linkPath>. " +
						s"Could lead to problems!",e)
			}
			
			//if we got this far we got the main work done. So we can always already return a success
			replyTo ! HttpResponse(NoContent)
			
			//todo: here we should actually search the file system for variants
			//  this could also be done by a cleanup actor
			// Things that should not be cleaned up:
			//   - <file.count> as that gives the count for the next version.
			//   - other ?
			//  This indicates that it would be good to have a link to the archive
			val variants = List() //we don't do variants yet
			val cleanup: List[FPath] = if linkPath == linkTo then variants.toList else linkTo::variants.toList
			cleanup.foreach{ p => 
				try {
					Files.move(p, archive.resolve(p.getFileName))
				} catch {
					case e: UnsupportedOperationException => // we don't even have a symlink. Problem!
						log.error(s"tried move <$p> to <$archive>. " +
							s"But OS does not support some attribute? JVM badly configured. ",e)
					case e: FileAlreadyExistsException =>
						log.warn(s"trying to move <$p> to <$archive> but a file with that name already exists. " +
							s"Will continue with DELETE operation.")
					case e: DirectoryNotEmptyException =>
						log.error(s"tried move link <$p> to <$archive>. " +
							s" But it turns out the moved object is a non empty directory!",e)
					case e: AtomicMoveNotSupportedException =>
						log.error(s"Tried moving <$p> to <$archive>. " +
							s"Message should not appear as Atomic Move was not requested. Corrupt JVM?",e)
					case e: SecurityException =>
						log.error(s"Security Exception trying move link <$p> to archive.",e)
					case e: IOException =>
						log.error(s"IOException trying move link <$p> to archive.",e)
				}
			}
			GoneBehavior
		end Delete

		/** Directory where an archive is stored before deletion */
		def archiveDir: FPath = linkPath.resolveSibling(linkPath.getFileName.toString + ".archive")


	}
}
