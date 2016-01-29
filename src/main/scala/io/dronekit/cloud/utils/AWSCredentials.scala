package io.dronekit.cloud.utils
/**
 * Created by Adam Villaflor on 11/30/2015.
 */

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import io.dronekit.cloud.SignRequestForAWS
import spray.json.DefaultJsonProtocol
import scala.io.Source
import spray.json._
import scala.concurrent.duration._
import scala.concurrent.{Promise, Await, ExecutionContext, Future}
import scala.util.Success


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
    val environment = sys.env
    val key_id = environment.get("AWS_ACCESS_KEY_ID")
    val access_key = environment.get("AWS_SECRET_ACCESS_KEY")
    valid_credentials(key_id, access_key)
  }

  def get_envCredentials_alt(): Option[AWSPermissions] = {
    val environment = sys.env
    val key_id = environment.get("AWS_ACCESS_KEY")
    val access_key = environment.get("AWS_SECRET_KEY")
    valid_credentials(key_id, access_key)
  }

  def get_javaSysCredentials(): Option[AWSPermissions] = {
    val access_key = Some(System.getProperty("aws.accessKeyId"))
    val key_id = Some(System.getProperty("aws.secretKey"))
    valid_credentials(key_id, access_key)
  }

  def get_specific_credentials_profile(credential_file:String, profile:String = "default"): Future[Option[AWSPermissions]] = {
    //    val prefs = new Ini(new File(filename));
    //    System.out.println("grumpy/homePage: " + prefs.node("grumpy").get("homePage", null));
    val header = """\s*\[([^]]*)\]\s*""".r
    val keyValue = """\s*([^=]*)=(.*)""".r
    //    val keyValue = """(\s) = (\s)""".r
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
        case e: Exception => println(s"""Could not open ${credential_file}""")
      }

      valid_credentials(key_id, access_key)
    }
  }
  def get_credentials_profile(profile:String = "default"): Future[Option[AWSPermissions]] = {
    val home = System.getProperty("user.home")
    val credential_file = home + "/.aws/credentials"
    get_specific_credentials_profile(credential_file,profile)
  }

  def get_Amazon_EC2_metadata_credentials(roleName:String): Future[Option[AWSPermissions]] = {
    import DefaultJsonProtocol._
    val URI = s"""http://169.254.169.254/latest/meta-data/iam/security-credentials/${roleName}"""
    val httpRequest = HttpRequest(method = HttpMethods.GET, uri = URI)
    val httpResponseFuture = SignRequestForAWS.post(httpRequest)
    httpResponseFuture.map{
      case response =>
        val responseData =  Await.result(response.entity.dataBytes.map(_.utf8String).grouped(Int.MaxValue).runWith(Sink.head), 10 seconds).mkString
        val responseJson = responseData.parseJson
        println(responseJson.prettyPrint)
        var key_id:Option[String] = None
        var access_key:Option[String] = None
        var token:Option[String] = None
        val jsonMap = responseJson.asJsObject().fields
        val js_key_id = jsonMap.get("AccessKeyId")
        if (js_key_id.isDefined)
          key_id = Some(js_key_id.get.toString())
        val js_access_key = jsonMap.get("SecretAccessKey")
        if (js_access_key.isDefined)
          access_key = Some(js_access_key.get.toString())
        val js_token = jsonMap.get("Token")
        if (js_token.isDefined)
          token = Some(js_token.get.toString())
        valid_credentials(key_id, access_key, token)
    }
  }

  def get_credentials(profile:String = "default", roleName:String = ""): Future[Option[AWSPermissions]] = {
    val p: Promise[Option[AWSPermissions]] = Promise()
    val envCredentials = Future.successful(get_envCredentials())
    val envCredentials_alt = Future.successful(get_envCredentials_alt())
    val javaSysCredentials = Future.successful(get_javaSysCredentials())
    val profileCredentials = get_credentials_profile(profile)
    val ecsCredentials = if (roleName != "") get_Amazon_EC2_metadata_credentials(roleName) else Future{None}
    val credentialProviderList: List[Future[Option[AWSPermissions]]] = List(envCredentials, envCredentials_alt, javaSysCredentials, profileCredentials, ecsCredentials)
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
    Future.sequence(credentialProviderList).map(_ collectFirst { case Some(x) => x })
  }

}
