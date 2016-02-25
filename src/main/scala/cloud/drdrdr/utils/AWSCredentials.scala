package cloud.drdrdr.utils

/**
 * Created by Adam Villaflor on 11/30/2015.
 */


import java.io.File

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.pattern.after
import cloud.drdrdr.SignRequestForAWS
import scala.io.Source
import akka.stream.scaladsl
import spray.json._
import scala.concurrent.duration._
import scala.concurrent.{Promise, Await, ExecutionContext, Future}
import scala.util.{Success, Failure}

trait AWSCredentials {

  case class AWSPermissions(accessKeyId: String, secretAccessKey: String, token: String = "")

  //checks if both the access key Id and the secret key are valid
  def validCredentials(key_id:Option[String], access_key:Option[String], token: Option[String] = None): Option[AWSPermissions] = {
    if (key_id.isDefined && access_key.isDefined && key_id.get != null && access_key.get != null)
      Some(AWSPermissions(key_id.get, access_key.get, token.getOrElse("")))
    else None
  }

  /**
   * gets the credentials from the environment fields AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY
   * @param ec implicit execution context
   * @param system implicit actor system
   * @param materializer implicit actor materializer
   * @return aws credentials or none
   */
  def getEnvironmentCredentials()(implicit ec: ExecutionContext, system:ActorSystem, materializer: ActorMaterializer): Option[AWSPermissions] = {
    getCredentialsFromMap(sys.env, "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY")
  }

  /**
   * gets the credentials from the environment fields AWS_ACCESS_KEY and AWS_SECRET_KEY
   * @param ec implicit execution context
   * @param system implicit actor system
   * @param materializer implicit actor materializer
   * @return aws credentials or none
   */
  def getEnvironmentAlternateCredentials_alt()(implicit ec: ExecutionContext, system:ActorSystem, materializer: ActorMaterializer): Option[AWSPermissions] = {
    getCredentialsFromMap(sys.env, "AWS_ACCESS_KEY", "AWS_SECRET_KEY")
  }

  //gets credentials from a generic string to string map
  protected def getCredentialsFromMap(environment:Map[String, String], key_id_key:String, access_key_key:String)(implicit ec: ExecutionContext, system:ActorSystem, materializer: ActorMaterializer) = {
    val key_id = environment.get(key_id_key)
    val access_key = environment.get(access_key_key)
    validCredentials(key_id, access_key)
  }

  /**
   * gets the credentials from the java system fields aws.accessKeyId and aws.secretKey
   * @param ec implicit execution context
   * @param system implicit actor system
   * @param materializer implicit actor materializer
   * @return aws credentials or none
   */
  def getJavaSystemCredentials()(implicit ec: ExecutionContext, system:ActorSystem, materializer: ActorMaterializer): Option[AWSPermissions] = {
    val key_id = Some(System.getProperty("aws.accessKeyId"))
    val access_key = Some(System.getProperty("aws.secretKey"))
    validCredentials(key_id, access_key)
  }

  /**
   * gets the credentials from a specific profile in a specified file
   * @param credential_file file with aws credentials
   * @param profile the name of the profile for the credentials to be used
   * @param ec implicit execution context
   * @param system implicit actor system
   * @param materializer implicit actor materializer
   * @return aws credentials or None
   */
  def getSpecificCredentialsProfile(credential_file:String, profile:String = "default")(implicit ec: ExecutionContext, system:ActorSystem, materializer: ActorMaterializer): Future[Option[AWSPermissions]] = {
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
                    case keyValue(key, value) => key_id = Some(value.trim())
                  }
                  lines.next() match {
                    case keyValue(key, value) => access_key = Some(value.trim())
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

  /**
   * gets the credentials from a specific profile in ~/.aws/credentials
   * @param profile the name of the profile for the credentials to be used
   * @param ec implicit execution context
   * @param system implicit actor system
   * @param materializer implicit actor materializer
   * @return aws credentials or None
   */
  def getCredentialsProfile(profile:String = "default")(implicit ec: ExecutionContext, system:ActorSystem, materializer: ActorMaterializer): Future[Option[AWSPermissions]] = {
    val home = System.getProperty("user.home")
    val credential_file = home + File.separator +  ".aws" + File.separator + "credentials"
    getSpecificCredentialsProfile(credential_file, profile)
  }

  //gets the credentials on a ec2 server for a roleName
  protected def getEC2RoleCredentials(roleName:String, timeout:Int = 500)(implicit ec: ExecutionContext, system:ActorSystem, materializer: ActorMaterializer): Future[Option[AWSPermissions]] = {
    import DefaultJsonProtocol._
    val URI = s"""http://169.254.169.254/latest/meta-data/iam/security-credentials/${roleName}"""
    val httpRequest = HttpRequest(method = HttpMethods.GET, uri = URI)
    val httpResponseFuture = post(httpRequest)
    val ec2Credentials = httpResponseFuture flatMap{
      case response:HttpResponse =>
        getCredentialsEC2Response(response)
    }
    Future.firstCompletedOf(
      List(ec2Credentials,
        after(timeout milliseconds, system.scheduler)(Future {None})))
  }

  //gets credentials from the http response of a ec2 instance
  protected def getCredentialsEC2Response(response: HttpResponse)(implicit ec: ExecutionContext, system:ActorSystem, materializer: ActorMaterializer): Future[Option[AWSPermissions]] = {
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

  //gets the role name off the ec2 instance
  protected def getAmazonEC2RoleName(timeout:Int = 500)(implicit ec: ExecutionContext, system:ActorSystem, materializer: ActorMaterializer): Future[Option[String]] = {
    val URI = s"""http://169.254.169.254/latest/meta-data/iam/info"""
    val httpRequest = HttpRequest(method = HttpMethods.GET, uri = URI)
    val httpResponseFuture = post(httpRequest)
    val roleName = httpResponseFuture flatMap {
      case response:HttpResponse =>
        response.entity.dataBytes.map(_.utf8String).grouped(Int.MaxValue).runWith(Sink.head) map {
          case responseInfo =>
            val responseData = responseInfo.mkString
            val responseJson = responseData.parseJson
            var role: Option[String] = None
            val jsonMap = responseJson.asJsObject().fields
            val js_instance_profile = jsonMap.get("InstanceProfileArn")
            if (js_instance_profile.isDefined) {
              val instance_sections = js_instance_profile.get.toString().split("/")
              if (instance_sections.nonEmpty)
                role = Some(instance_sections.last replaceAll ("[\"]", ""))
            }
            role
        }
    }
    Future.firstCompletedOf(
      List(roleName,
        after(timeout milliseconds, system.scheduler)(Future {None})))
  }

  /**
   * gets the aws credentials associated with the role of the ec2 instance
   * @param timeout time to wait for the ec2 response in miliseconds
   * @param ec implicit execution context
   * @param system implicit actor system
   * @param materializer implicit actor materializer
   * @return aws credentials or None
   */
  def getAmazonEC2Credentials(timeout:Int = 500)(implicit ec: ExecutionContext, system:ActorSystem, materializer: ActorMaterializer): Future[Option[AWSPermissions]] = {
    val roleName = getAmazonEC2RoleName(timeout)
    val ec2Credentials = roleName flatMap{
      case Some(role) =>
        getEC2RoleCredentials(role, timeout)
      case _ => Future{None}
    }
    ec2Credentials
  }

  /**
   * gets the first aws credentials it finds by checking the environment, java system, local credential file, and ec2 instance in that respective order
   * gets the credentials from a specific profile in a specified file
   * @param credential_file file with aws credentials
   * @param profile the name of the profile for the credentials to be used
   * @param timeout time to wait for the ec2 response in miliseconds
   * @param ec implicit execution context
   * @param system implicit actor system
   * @param materializer implicit actor materializer
   * @return aws credentials or None
   */
  def getCredentials(profile:String = "default", credential_file:String = "", timeout:Int = 500)(implicit ec: ExecutionContext, system:ActorSystem, materializer: ActorMaterializer): Future[Option[AWSPermissions]] = {
    val p: Promise[Option[AWSPermissions]] = Promise()
    val envCredentials = Future.successful(getEnvironmentCredentials())
    val envCredentials_alt = Future.successful(getEnvironmentAlternateCredentials_alt())
    val javaSysCredentials = Future.successful(getJavaSystemCredentials())
    val profileCredentials = if (credential_file == "") getCredentialsProfile(profile) else getSpecificCredentialsProfile(credential_file, profile)
    val ec2Credential = getAmazonEC2Credentials()
    val credentialProviderList: List[Future[Option[AWSPermissions]]] = List(envCredentials, envCredentials_alt, javaSysCredentials, profileCredentials, ec2Credential)

    futureList(credentialProviderList, 0)
  }

  private def futureList(futureSeq: List[Future[Option[AWSPermissions]]], index: Int)
                        (implicit ec: ExecutionContext, system:ActorSystem, materializer: ActorMaterializer): Future[Option[AWSPermissions]] = {
    futureSeq(index) flatMap  {
      case Some(result) => Future.successful(Some(result))
      case None => if (index == futureSeq.length - 1) Future.successful(None) else futureList(futureSeq, index + 1)
    }
  }

  //sends outgoing request
  private def post(httpRequest: HttpRequest)(implicit ec: ExecutionContext, system:ActorSystem, materializer: ActorMaterializer): Future[HttpResponse] = {
    val endpoint = httpRequest.uri.toString()
    val uri = java.net.URI.create(endpoint)
    val outgoingConn = if (uri.getScheme == "https") {
      Http().outgoingConnectionHttps(uri.getHost, if (uri.getPort == -1) 443 else uri.getPort)
    } else {
      Http().outgoingConnection(uri.getHost, if (uri.getPort == -1) 80 else uri.getPort)
    }
    scaladsl.Source.single(httpRequest).via(outgoingConn).runWith(Sink.head)
  }
}

object AWSCredentials extends AWSCredentials
