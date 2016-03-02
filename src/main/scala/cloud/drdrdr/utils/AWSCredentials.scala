package cloud.drdrdr.utils

/**
 * Created by Adam Villaflor on 11/30/2015.
 */


import java.io.File
import java.text.SimpleDateFormat
import java.util.{Date, SimpleTimeZone}

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.pattern.after
import akka.stream.{ActorMaterializer, scaladsl}
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import spray.json._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source

trait AWSCredentials {

  class AWSPermissions(keyId: String, secretKey: String, token: String = "") {
    private val accessKey = keyId
    private val secretAccess = secretKey
    def accessKeyId: String = {
      this.accessKey
    }
    def secretAccessKey:  String = {
      this.secretAccess
    }
    def token: String = {
      ""
    }
  }

//  class EC2Permissions(accessKey: Future[String], secretAccess: Future[String], tok: Future[String], exp: Future[String]) {
//    private var accessKeyId = accessKeyId
//    private var secretAccessKey = secretAccess
//    private var token = tok
//    private var expires = exp
//    def getAccessKeyId:  Future[String] = {
//      update()
//      this.secretAccessKey
//    }
//    def getSecretAccessKey: Future[String] = {
//      update()
//      this.accessKeyId
//    }
//    def getToken: Future[String] = {
//      update()
//      this.token
//    }
//    def update: Unit = {
//      val needUpdate = checkExpiration
//      needUpdate map {
//        case bool:Boolean =>
//          if (bool) {
//            val credentials =  getAmazonEC2Credentials()
//            // need to set the credentials to the new ones
//            credentials map {
//              case Some(permissions) =>
//              case None => ;
//            }
//          }
//      }
//    }
//    def checkExpiration: Future[Boolean] = {
//      this.expires map {
//        case exp:String =>
//          getUTCTime > exp
//      }
//    }
//  }

  //checks if both the access key Id and the secret key are valid
  def validCredentials(keyId: Option[String], accessKey: Option[String]): Option[AWSPermissions] = {
    if (keyId.isDefined && accessKey.isDefined && keyId.get != null && accessKey.get != null)
      Some(new AWSPermissions(keyId.get, accessKey.get))
    else None
  }

  /**
   * gets the credentials from the environment fields AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY
 *
   * @param ec implicit execution context
   * @param s implicit actor system
   * @param m implicit actor materializer
   * @return aws credentials or none
   */
  def getEnvironmentCredentials()(implicit ec: ExecutionContext, s:ActorSystem, m: ActorMaterializer):
  Option[AWSPermissions] = {
    getCredentialsFromMap(sys.env, "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY")
  }

  /**
   * gets the credentials from the environment fields AWS_ACCESS_KEY and AWS_SECRET_KEY
 *
   * @param ec implicit execution context
   * @param s implicit actor system
   * @param m implicit actor materializer
   * @return aws credentials or none
   */
  def getEnvironmentAlternateCredentials()(implicit ec: ExecutionContext, s:ActorSystem, m: ActorMaterializer):
  Option[AWSPermissions] = {
    getCredentialsFromMap(sys.env, "AWS_ACCESS_KEY", "AWS_SECRET_KEY")
  }

  //gets credentials from a generic string to string map
  protected def getCredentialsFromMap(environment:Map[String, String], keyIdKey:String, accessKeyKey:String)
                                     (implicit ec: ExecutionContext, s:ActorSystem, m: ActorMaterializer):
  Option[AWSPermissions] = {
    val keyId = environment.get(keyIdKey)
    val accessKey = environment.get(accessKeyKey)
    validCredentials(keyId, accessKey)
  }

  /**
   * gets the credentials from the java system fields aws.accessKeyId and aws.secretKey
 *
   * @param ec implicit execution context
   * @param s implicit actor system
   * @param m implicit actor materializer
   * @return aws credentials or none
   */
  def getJavaSystemCredentials()(implicit ec: ExecutionContext, s:ActorSystem, m: ActorMaterializer):
  Option[AWSPermissions] = {
    val keyId = Some(System.getProperty("aws.accessKeyId"))
    val accessKey = Some(System.getProperty("aws.secretKey"))
    validCredentials(keyId, accessKey)
  }

  /**
   * gets the credentials from a specific profile in a specified file
 *
   * @param credentialFile file with aws credentials
   * @param profile the name of the profile for the credentials to be used
   * @param ec implicit execution context
   * @param s implicit actor system
   * @param m implicit actor materializer
   * @return aws credentials or None
   */
  def getSpecificCredentialsProfile(credentialFile:String, profile:String = "default")
                                   (implicit ec: ExecutionContext, s:ActorSystem, m: ActorMaterializer):
  Future[Option[AWSPermissions]] = {
    val header = """\s*\[([^]]*)\]\s*""".r
    val keyValue = """\s*([^=]*)=(.*)""".r
    var accessKey : Option[String] = None
    var keyId : Option[String] = None
    Future {
      try {
        val source = Source.fromFile(credentialFile)
        val lines = source.getLines()
        try {
          while (lines.hasNext && accessKey.isEmpty && keyId.isEmpty) {
            lines.next() match {
              case header(head) =>
                if (head.equals(profile)) {
                  lines.next() match {
                    case keyValue(key, value) => keyId = Some(value.trim())
                  }
                  lines.next() match {
                    case keyValue(key, value) => accessKey = Some(value.trim())
                  }
                }
              case _ => ;
            }
          }
        } catch {
          case e: Exception =>
            println( s"""$credentialFile file does not contain $profile or is improperly formatted""")
        }
        source.close()
      } catch {
        case e: Exception => println(s"""Could not open $credentialFile due to $e""")
      }

      validCredentials(keyId, accessKey)
    }
  }

  /**
   * gets the credentials from a specific profile in ~/.aws/credentials
   *
   * @param profile the name of the profile for the credentials to be used
   * @param ec implicit execution context
   * @param s implicit actor system
   * @param m implicit actor materializer
   * @return aws credentials or None
   */
  def getCredentialsProfile(profile:String = "default")
                           (implicit ec: ExecutionContext, s:ActorSystem, m: ActorMaterializer):
  Future[Option[AWSPermissions]] = {
    val home = System.getProperty("user.home")
    val credentialFile = home + File.separator +  ".aws" + File.separator + "credentials"
    getSpecificCredentialsProfile(credentialFile, profile)
  }

  // gets the credentials on a ec2 server for a roleName
  protected def getEC2RoleCredentials(roleName: String, timeout: Int = 500)
                                     (implicit ec: ExecutionContext, s:ActorSystem, m: ActorMaterializer):
  Future[Option[AWSPermissions]] = {
    val URI = s"""http://169.254.169.254/latest/meta-data/iam/security-credentials/$roleName"""
    val httpRequest = HttpRequest(method = HttpMethods.GET, uri = URI)
    val httpResponseFuture = post(httpRequest)
    val ec2Credentials = httpResponseFuture flatMap{
      case response:HttpResponse =>
        getCredentialsEC2Response(response)
    }
    Future.firstCompletedOf(
      List(ec2Credentials, after(timeout milliseconds, s.scheduler)(Future {None})))
  }

  //gets credentials from the http response of a ec2 instance
  protected def getCredentialsEC2Response(response: HttpResponse)
                                         (implicit ec: ExecutionContext, s:ActorSystem, m: ActorMaterializer):
  Future[Option[AWSPermissions]] = {
    response.entity.dataBytes.map(_.utf8String).grouped(Int.MaxValue).runWith(Sink.head) map {
      case responseInfo =>
        val responseData = responseInfo.mkString
        val responseJson = responseData.parseJson
        var keyId:Option[String] = None
        var accessKey:Option[String] = None
        var token:Option[String] = None
        val jsonMap = responseJson.asJsObject().fields
        val jsKeyId = jsonMap.get("AccessKeyId")
        if (jsKeyId.isDefined)
          keyId = Some(jsKeyId.get.toString() replaceAll ("[\"]", ""))
        val jsAccessKey = jsonMap.get("SecretAccessKey")
        if (jsAccessKey.isDefined)
          accessKey = Some(jsAccessKey.get.toString() replaceAll ("[\"]", ""))
        val jsToken = jsonMap.get("Token")
        if (jsToken.isDefined)
          token = Some(jsToken.get.toString() replaceAll ("[\"]", ""))
        //fix
        validCredentials(keyId, accessKey) }
  }

  // gets the role name off the ec2 instance
  protected def getAmazonEC2RoleName(timeout: Int = 500)
                                    (implicit ec: ExecutionContext, s:ActorSystem, m: ActorMaterializer):
  Future[Option[String]] = {
    import DefaultJsonProtocol._
    import spray.json._

    val request = HttpRequest(HttpMethods.GET, "http://169.254.169.254/latest/meta-data/iam/info")
    val instanceFuture = Http().singleRequest(request).flatMap{ response =>
      response.entity.dataBytes
        .fold(ByteString.empty)(_ ++ _)
        .map(_.utf8String)
        .runWith(Sink.head)
        .map(_.parseJson)
        .map{_.asJsObject.fields.get("InstanceProfileArn")
            .map(_.toString().split("/").last.replaceAll("[\"]", ""))
        }
    }
    Future.firstCompletedOf(
      List(instanceFuture, after(timeout milliseconds, s.scheduler)(Future.successful(None))))
  }

  /**
   * gets the aws credentials associated with the role of the ec2 instance
 *
   * @param timeout time to wait for the ec2 response in miliseconds
   * @param ec implicit execution context
   * @param s implicit actor system
   * @param m implicit actor materializer
   * @return aws credentials or None
   */
  def getAmazonEC2Credentials(timeout: Int = 500)(implicit ec: ExecutionContext, s:ActorSystem, m: ActorMaterializer):
  Future[Option[AWSPermissions]] = {
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
 *
   * @param credentialFile file with aws credentials
   * @param profile the name of the profile for the credentials to be used
   * @param timeout time to wait for the ec2 response in miliseconds
   * @param ec implicit execution context
   * @param s implicit actor system
   * @param m implicit actor materializer
   * @return aws credentials or None
   */
  def getCredentials(profile: String = "default", credentialFile: String = "", timeout: Int = 500)
                    (implicit ec: ExecutionContext, s: ActorSystem, m: ActorMaterializer):
  Future[Option[AWSPermissions]] = {
    val envCredentials = Future.successful(getEnvironmentCredentials())
    val envCredentialsAlt = Future.successful(getEnvironmentAlternateCredentials())
    val javaSysCredentials = Future.successful(getJavaSystemCredentials())
    val profileCredentials =
      if (credentialFile == "") getCredentialsProfile(profile)
      else getSpecificCredentialsProfile(credentialFile, profile)
    //fix
    val ec2Credential = getAmazonEC2Credentials()
    val credentialProviderList: List[Future[Option[AWSPermissions]]] =
      List(envCredentials, envCredentialsAlt, javaSysCredentials, profileCredentials, ec2Credential)

    futureList(credentialProviderList)
  }

  private def futureList(futureSeq: List[Future[Option[AWSPermissions]]])
                        (implicit ec: ExecutionContext, system:ActorSystem, materializer: ActorMaterializer):
  Future[Option[AWSPermissions]] = {
    futureSeq.head flatMap  {
      case Some(result) => Future.successful(Some(result))
      case None =>
        if (futureSeq.isEmpty) Future.successful(None)
        else futureList(futureSeq.tail)
    }
  }

  // sends outgoing request
  private def post(httpRequest: HttpRequest)(implicit ec: ExecutionContext, s: ActorSystem, m: ActorMaterializer):
  Future[HttpResponse] = {
    val endpoint = httpRequest.uri.toString()
    val uri = java.net.URI.create(endpoint)
    val outgoingConn = if (uri.getScheme == "https") {
      Http().outgoingConnectionHttps(uri.getHost, if (uri.getPort == -1) 443 else uri.getPort)
    } else {
      Http().outgoingConnection(uri.getHost, if (uri.getPort == -1) 80 else uri.getPort)
    }
    scaladsl.Source.single(httpRequest).via(outgoingConn).runWith(Sink.head)
  }

  // got utc time for amz date from http://stackoverflow.com/questions/25991892/how-do-i-format-time-to-utc-time-zone
  // got formatting from http://stackoverflow.com/questions/5377790/date-conversion
  // formatting based on convention for amz signing
  protected def getUTCTime(): String = {
    val date = new Date()
    val format1 = new SimpleDateFormat("yyyy-MM-dd")
    format1.setTimeZone(new SimpleTimeZone(SimpleTimeZone.UTC_TIME, "UTC"))
    val format2 = new SimpleDateFormat("HH:mm:ss")
    format2.setTimeZone(new SimpleTimeZone(SimpleTimeZone.UTC_TIME, "UTC"))
    format1.format(date) + "T" + format2.format(date) + "Z"
  }

}

object AWSCredentials extends AWSCredentials
