package io.dronekit.cloud.utils
/**
 * Created by Adam Villaflor on 11/30/2015.
 */

import java.util.concurrent.TimeoutException

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.pattern.after
import io.dronekit.cloud.SignRequestForAWS
import spray.json.DefaultJsonProtocol
import scala.io.Source
import spray.json._
import scala.concurrent.duration._
import scala.concurrent.{Promise, Await, ExecutionContext, Future}
import scala.util.{Success, Failure}


object AWSCredentials {

  implicit val system: ActorSystem = ActorSystem()
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  case class AWSPermissions(accessKeyId: String, secretAccessKey: String, token: String = "")

  def valid_credentials(key_id:Option[String], access_key:Option[String], token: Option[String] = Some("")): Option[AWSPermissions] = {
    if (key_id.isDefined && access_key.isDefined && key_id.get != null && access_key.get != null)
      Some(AWSPermissions(key_id.get, access_key.get, token.get))
    else None
  }

  def get_envCredentials(): Option[AWSPermissions] = {
    get_credentials_from_map(sys.env, "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY")
  }

  def get_envCredentials_alt(): Option[AWSPermissions] = {
    get_credentials_from_map(sys.env, "AWS_ACCESS_KEY", "AWS_SECRET_KEY")
  }

  def get_credentials_from_map(environment:Map[String, String], key_id_key:String, access_key_key:String) = {
    val key_id = environment.get(key_id_key)
    val access_key = environment.get(access_key_key)
    valid_credentials(key_id, access_key)
  }

  def get_javaSysCredentials(): Option[AWSPermissions] = {
    val key_id = Some(System.getProperty("aws.accessKeyId"))
    val access_key = Some(System.getProperty("aws.secretKey"))
    valid_credentials(key_id, access_key)
  }

  def get_specific_credentials_profile(credential_file:String, profile:String = "default"): Future[Option[AWSPermissions]] = {
    val header = """\s*\[([^]]*)\]\s*""".r
    val keyValue = """\s*([^=]*)=(.*)""".r
    var access_key : Option[String] = None
    var key_id : Option[String] = None
    Future {
      try {
        val source = Source.fromFile(credential_file)
        val lines = source.getLines()
        try {
          while (lines.hasNext && access_key.isEmpty && key_id.isEmpty) {
            lines.next() match {
              case header(head) =>
                if (head.equals(profile)) {
                  lines.next() match {
                    case keyValue(key, value) => key_id = Some(value)
                  }
                  lines.next() match {
                    case keyValue(key, value) => access_key = Some(value)
                  }
                }
              case _ => ;
            }
          }
        } catch {
          case e: Exception =>
            println( s"""${credential_file} file does not contain ${profile} or is improperly formatted""")
        }
        source.close()
      } catch {
        case e: Exception => println(s"""Could not open $credential_file due to ${e}""")
      }

      valid_credentials(key_id, access_key)
    }
  }
  def get_credentials_profile(profile:String = "default"): Future[Option[AWSPermissions]] = {
    val home = System.getProperty("user.home")
    val credential_file = home + "/.aws/credentials"
    get_specific_credentials_profile(credential_file,profile)
  }

  def get_Amazon_EC2_metadata_credentials(roleName:String, timeout:Int = 500): Future[Option[AWSPermissions]] = {
    import DefaultJsonProtocol._
    val URI = s"""http://169.254.169.254/latest/meta-data/iam/security-credentials/${roleName}"""
    val httpRequest = HttpRequest(method = HttpMethods.GET, uri = URI)
    val httpResponseFuture = SignRequestForAWS.post(httpRequest)
    val ec2Credentials = httpResponseFuture flatMap{
      case response:HttpResponse =>
        get_credentials_EC2_response(response)
    }
    Future.firstCompletedOf(
      List(ec2Credentials,
        after(timeout milliseconds, system.scheduler)(Future {None})))
  }

  def get_credentials_EC2_response(response: HttpResponse): Future[Option[AWSPermissions]] = {
    response.entity.dataBytes.map(_.utf8String).grouped(Int.MaxValue).runWith(Sink.head) map {
      case responseInfo =>
        val responseData = responseInfo.mkString
        val responseJson = responseData.parseJson
        var key_id:Option[String] = None
        var access_key:Option[String] = None
        var token:Option[String] = None
        val jsonMap = responseJson.asJsObject().fields
        val js_key_id = jsonMap.get("AccessKeyId")
        if (js_key_id.isDefined)
          key_id = Some(js_key_id.get.toString() replaceAll ("[\"]", ""))
        val js_access_key = jsonMap.get("SecretAccessKey")
        if (js_access_key.isDefined)
          access_key = Some(js_access_key.get.toString() replaceAll ("[\"]", ""))
        val js_token = jsonMap.get("Token")
        if (js_token.isDefined)
          token = Some(js_token.get.toString() replaceAll ("[\"]", ""))
        valid_credentials(key_id, access_key, token) }
  }

  def get_credentials(profile:String = "default", roleName:String = "", credential_file:String = ""): Future[Option[AWSPermissions]] = {
    val p: Promise[Option[AWSPermissions]] = Promise()
    val envCredentials = Future.successful(get_envCredentials())
    val envCredentials_alt = Future.successful(get_envCredentials_alt())
    val javaSysCredentials = Future.successful(get_javaSysCredentials())
    val profileCredentials = if (credential_file == "") get_credentials_profile(profile) else get_specific_credentials_profile(credential_file, profile)
    val ec2Credential = if (roleName.length > 0) get_Amazon_EC2_metadata_credentials(roleName) else Future{None}
    val credentialProviderList: List[Future[Option[AWSPermissions]]] = List(envCredentials, envCredentials_alt, javaSysCredentials, profileCredentials, ec2Credential)
    //    envCredentials.onComplete { case Success(perm) => if (perm.isDefined) perm
    //    else
    //      envCredentials_alt.onComplete { case Success(perm:Option[AWSPermissions]) => if (perm.isDefined) p.trySuccess(perm)
    //      else
    //        javaSysCredentials.onComplete { case Success(perm:Option[AWSPermissions]) => if (perm.isDefined) p.trySuccess(perm)
    //        else
    //          profileCredentials.onComplete { case Success(perm:Option[AWSPermissions]) => if (perm.isDefined) p.trySuccess(perm)
    //          else
    //            ecsCredentials.onComplete { case Success(perm:Option[AWSPermissions]) => if (perm.isDefined) p.trySuccess(perm)
    //            else
    //              p.trySuccess(None)
    //            }
    //          }
    //        }
    //      }
    //    }
    //    p.future
//    val futureCredentials = Future.sequence(credentialProviderList).map(_ collectFirst { case Some(x) => x })
//    futureCredentials onComplete {
//      case Success(credential) => if (credential.isEmpty) {
//        if (roleName != "") get_Amazon_EC2_metadata_credentials(roleName)
//        else Future {None}
//      }
//      case Failure(t) =>
//        if (roleName != "") get_Amazon_EC2_metadata_credentials(roleName)
//        else Future {None}
//    }
    Future.sequence(credentialProviderList).map(_ collectFirst { case Some(x) => x})
  }
}
