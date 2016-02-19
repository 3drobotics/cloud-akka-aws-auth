package cloud.drdrdr.utils

/**
 * Created by Adam Villaflor on 11/30/2015.
 */


import java.io.File

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.pattern.after
import cloud.drdrdr.SignRequestForAWS
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

  def validCredentials(key_id:Option[String], access_key:Option[String], token: Option[String] = Some("")): Option[AWSPermissions] = {
    if (key_id.isDefined && access_key.isDefined && key_id.get != null && access_key.get != null)
      Some(AWSPermissions(key_id.get, access_key.get, token.get))
    else None
  }

  def getEnvironmentCredentials(): Option[AWSPermissions] = {
    getCredentialsFromMap(sys.env, "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY")
  }

  def getEnvironmentAlternateCredentials(): Option[AWSPermissions] = {
    getCredentialsFromMap(sys.env, "AWS_ACCESS_KEY", "AWS_SECRET_KEY")
  }

  def getCredentialsFromMap(environment:Map[String, String], key_id_key:String, access_key_key:String) = {
    val key_id = environment.get(key_id_key)
    val access_key = environment.get(access_key_key)
    validCredentials(key_id, access_key)
  }

  def getJavaSystemCredentials(): Option[AWSPermissions] = {
    val key_id = Some(System.getProperty("aws.accessKeyId"))
    val access_key = Some(System.getProperty("aws.secretKey"))
    validCredentials(key_id, access_key)
  }

  def getSpecificCredentialsProfile(credential_file: String, profile: String = "default"): Future[Option[AWSPermissions]] = {
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
            println( s"""$credential_file file does not contain $profile or is improperly formatted""")
        }
        source.close()
      } catch {
        case e: Exception => println(s"""Could not open $credential_file due to $e""")
      }

      validCredentials(key_id, access_key)
    }
  }
  def getCredentialsProfile(profile: String = "default"): Future[Option[AWSPermissions]] = {
    val home = System.getProperty("user.home")
    val credential_file = home + File.separator +  ".aws" + File.separator + "credentials"
    getSpecificCredentialsProfile(credential_file, profile)
  }

  def getAmazonEC2Credentials(roleName:String, timeout:Int = 500): Future[Option[AWSPermissions]] = {
    val URI = s"""http://169.254.169.254/latest/meta-data/iam/security-credentials/$roleName"""
    val httpRequest = HttpRequest(method = HttpMethods.GET, uri = URI)
    val httpResponseFuture = SignRequestForAWS.post(httpRequest)
    val ec2Credentials = httpResponseFuture flatMap{
      case response:HttpResponse =>
        getCredentialsEC2Response(response)
    }
    Future.firstCompletedOf(
      List(ec2Credentials,
        after(timeout milliseconds, system.scheduler)(Future {None})))
  }

  def getCredentialsEC2Response(response: HttpResponse): Future[Option[AWSPermissions]] = {
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
        validCredentials(key_id, access_key, token) }
  }

  def getCredentials(profile:String = "default", roleName:String = "", credential_file:String = ""): Future[Option[AWSPermissions]] = {
    val p: Promise[Option[AWSPermissions]] = Promise()
    val envCredentials = Future.successful(getEnvironmentCredentials())
    val envCredentials_alt = Future.successful(getEnvironmentAlternateCredentials())
    val javaSysCredentials = Future.successful(getJavaSystemCredentials())
    val profileCredentials = if (credential_file == "") getCredentialsProfile(profile) else getSpecificCredentialsProfile(credential_file, profile)
    val ec2Credential = if (roleName.length > 0) getAmazonEC2Credentials(roleName) else Future{None}
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
