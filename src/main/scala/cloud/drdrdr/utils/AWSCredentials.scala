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
import scala.concurrent.{ExecutionContext, Future, Await}
import scala.io.Source
import scala.util.control


trait AWSCredentials {

  case class AWSCredentialSource(creds: Future[AWSPermissions]) (implicit ec: ExecutionContext, s:ActorSystem, m: ActorMaterializer) {
    private var credentials: Future[AWSPermissions] = creds
    def getCredentials: Future[AWSPermissions] = {
      credentials flatMap {
        case perm:AWSPermissions =>
          if (perm.expiration.isEmpty) {
              credentials
          } else {
              checkExpire flatMap {
                case bol =>
                  if (bol) {
                    credentials = updateCredentials()
                  }
                  credentials
              }
          }
      }
    }

//  sealed trait AWSCredentialSource {
//    protected var credentials: Future[AWSPermissions]
//    def getCredentials: Future[AWSPermissions]
//  }
//  case class staticSource(creds: Future[AWSPermissions]) extends AWSCredentialSource{
//    protected var credentials: Future[AWSPermissions] = creds
//    def getCredentials(): Future[AWSPermissions] = {
//      credentials
//    }
//  }
//  case class EC2Source(creds: Future[AWSPermissions])(implicit ec: ExecutionContext, s: ActorSystem, m: ActorMaterializer) extends AWSCredentialSource{
//    protected var credentials = creds
//    def getCredentials(): Future[AWSPermissions] = {
//      checkExpire flatMap {
//        case bol =>
//          if (bol) {
//            credentials = updateCredentials()
//          }
//          credentials
//      }
//    }
    //checks if current credentials have expired
    private def checkExpire()(implicit ec: ExecutionContext, s: ActorSystem, m: ActorMaterializer): Future[Boolean] = {
      credentials map {
        case creds: AWSPermissions =>
          if (creds.expiration.isEmpty)
            false
          else
            creds.expiration <= getUTCTime()
      }
    }
//
    //updates the credentials on an EC2 instance if necessary
    @throws(classOf[Exception])
    private def updateCredentials(): Future[AWSPermissions] = {
      getAmazonEC2Credentials() map {
        case Some(cred) => cred
        case None => throw new Exception("Unable to update credentials")
      }
    }
  }
//
//  def makeSource(permissions: Future[AWSPermissions])(implicit ec: ExecutionContext, s: ActorSystem, m: ActorMaterializer): AWSCredentialSource = {
//    permissions map {
//      case perm =>
//        if (perm.expiration.isEmpty) {
//
//        } else {
//
//        }
//    }
//  }

//  def getCurrentCredentials(awsCredSource: AWSCredentialSource): Future[AWSPermissions] = {
//    awsCredSource match {
//      case static:staticSource =>
//      case ec2:EC2Source =>
//    }
//  }

  /**
   *
   * @param keyId aws accessKeyId
   * @param secretKey aws accessSecretKey
   * @param tok aws token associated with credentials
   * @param exp aws expiration of credentials
   */
  class AWSPermissions(keyId: String, secretKey: String, tok: String = "", exp: String = "") {
    private val accessKey = keyId
    private val secretAccess = secretKey
    private val t = tok
    private val e = exp

    /**
     * accessKeyId accessor method
     * @return accessKeyId
     */
    def accessKeyId: String = {
      this.accessKey
    }

    /**
     * secretAccessKey accessor method
     * @return secretAccessKey
     */
    def secretAccessKey: String = {
      this.secretAccess
    }

    /**
     * token accessor method
     * @return token
     */
    def token: String = {
      this.t
    }

    /**
     * expiration accessor method
     * @return expiration
     */
    def expiration: String = {
      this.e
    }


//    /**
//     * returns the equivalent futureAWSPermission with the same credentials
//     * @param ec implicit execution context
//     * @param s implicit actor system
//     * @param m implicit actor materializer
//     * @return futureAWSPermissions with the same credentials
//     */
//    def makeFuture()(implicit ec: ExecutionContext, s: ActorSystem, m: ActorMaterializer): updatingAWSPermissions = {
//      new updatingAWSPermissions(Future.successful(accessKeyId), Future.successful(secretAccessKey), Future.successful(token), Future.successful(exp))
//    }
  }

//  /**
//   * constructor for ec2 permission that will update itself when it expires
//   * @param keyId future aws accessKeyId
//   * @param secretKey future aws secretAccessKey
//   * @param tok future aws token associated with credentials
//   * @param exp future aws expiration of credentials
//   * @param ec implicit execution context
//   * @param s implicit actor system
//   * @param m implicit actor materializer
//   */
//  class updatingAWSPermissions(keyId: Future[String], secretKey: Future[String], tok: Future[String], exp: Future[String])(implicit ec: ExecutionContext, s: ActorSystem, m: ActorMaterializer) {
//    private var accessKey = keyId
//    private var secretAccess = secretKey
//    private var t = tok
//    private var e = exp
//
//    /**
//     * accessKeyId accessor method
//     * updates the credentials if they have expired
//     * @return future accessKeyId
//     */
//    def accessKeyId: Future[String] = {
//      checkExpire flatMap {
//        case bol =>
//          if (bol) {
//            updateCredentials();
//          }
//          accessKey
//      }
//    }
//
//    /**
//     * secretAccessKey accessor method
//     * updates the credentials if they have expired
//     * @return future secretAccessKey
//     */
//    def secretAccessKey: Future[String] = {
//      checkExpire flatMap {
//        case bol =>
//          if (bol) {
//            updateCredentials();
//          }
//          secretAccess
//      }
//    }
//
//    /**
//     * token accessor method
//     * updates the credentials if they have expired
//     * @return future token
//     */
//    def token: Future[String] = {
//      checkExpire flatMap {
//        case bol =>
//          if (bol) {
//            updateCredentials();
//          }
//          t
//      }
//    }
//
//    /**
//     * expiration accessor method
//     * @return future of expiration
//     */
//    def expiration: Future[String] = {
//      e
//    }
//
//    //checks if current credentials have expired
//    private def checkExpire: Future[Boolean] = {
//      expiration map {
//        case expireTime: String =>
//          if (expireTime.isEmpty)
//            false
//          else
//            expireTime >= getUTCTime()
//      }
//    }
//
//    //updates the credentials on an EC2 instance if necessary
//    private def updateCredentials(): Unit = {
//      val futureCredentials = getAmazonEC2Credentials()
//      update(futureCredentials)
//    }
//
//    //updates the credentials with a new permission
//    private def update(newPerm: Future[Option[AWSPermissions]]): Unit = {
//      accessKey = newPerm map { case Some(perm) => perm.accessKeyId }
//      secretAccess = newPerm map { case Some(perm) => perm.accessKeyId }
//      t = newPerm map { case Some(perm) => perm.token }
//      e = newPerm map { case Some(perm) => perm.expiration }
//    }
//
//    // got utc time for amz date from http://stackoverflow.com/questions/25991892/how-do-i-format-time-to-utc-time-zone
//    // got formatting from http://stackoverflow.com/questions/5377790/date-conversion
//    // formatting based on convention for amz signing
//    private def getUTCTime()(implicit ec: ExecutionContext, system: ActorSystem, materializer: ActorMaterializer): String = {
//      val date = new Date()
//      val format1 = new SimpleDateFormat("yyyy-MM-dd")
//      format1.setTimeZone(new SimpleTimeZone(SimpleTimeZone.UTC_TIME, "UTC"))
//      val format2 = new SimpleDateFormat("HH:mm:ss")
//      format2.setTimeZone(new SimpleTimeZone(SimpleTimeZone.UTC_TIME, "UTC"))
//      format1.format(date) + "T" + format2.format(date) + "Z"
//    }
//
//    /**
//     * awaits for the different credential fields and returns AWSPermissions with those credentials
//     * should only use if the credentials are permanent or if credentials will not expire
//     * @param timeout how long to wait for the fields
//     * @param ec implicit execution context
//     * @param s implicit actor system
//     * @param m implicit actor materializer
//     * @return static permissions
//     */
//    def removeFuture(timeout: Int = 50)(implicit ec: ExecutionContext, s: ActorSystem, m: ActorMaterializer): AWSPermissions = {
//      return new AWSPermissions(Await.result(accessKeyId, timeout milliseconds), Await.result(secretAccessKey, timeout milliseconds), Await.result(token, timeout milliseconds), Await.result(expiration, timeout milliseconds))
//    }
//  }

  //checks if both the access key Id and the secret key are valid
  def validCredentials(keyId: Option[String], accessKey: Option[String], token: Option[String] = Some(""), expiration: Option[String] = Some("")): Option[AWSPermissions] = {
    if (keyId.isDefined && accessKey.isDefined && keyId.get != null && accessKey.get != null)
      Some(new AWSPermissions(keyId.get, accessKey.get, token.get, expiration.get))
    else None
  }

  /**
   * gets the credentials from the environment fields AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY
   * @param ec implicit execution context
   * @param s implicit actor system
   * @param m implicit actor materializer
   * @return aws credentials or none
   */
  def getEnvironmentCredentials()(implicit ec: ExecutionContext, s: ActorSystem, m: ActorMaterializer): Option[AWSPermissions] = {
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
  def getEnvironmentAlternateCredentials()(implicit ec: ExecutionContext, s: ActorSystem, m: ActorMaterializer): Option[AWSPermissions] = {
    getCredentialsFromMap(sys.env, "AWS_ACCESS_KEY", "AWS_SECRET_KEY")
  }

  //gets credentials from a generic string to string map
  protected def getCredentialsFromMap(environment: Map[String, String], keyIdKey: String, accessKeyKey: String)
                                     (implicit ec: ExecutionContext, s: ActorSystem, m: ActorMaterializer): Option[AWSPermissions] = {
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
  def getJavaSystemCredentials()(implicit ec: ExecutionContext, s: ActorSystem, m: ActorMaterializer): Option[AWSPermissions] = {
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
  def getSpecificCredentialsProfile(credentialFile: String, profile: String = "default")
                                   (implicit ec: ExecutionContext, s: ActorSystem, m: ActorMaterializer): Future[Option[AWSPermissions]] = {
    val header = """\s*\[([^]]*)\]\s*""".r
    val keyValue = """\s*([^=]*)=(.*)""".r
    var accessKey: Option[String] = None
    var keyId: Option[String] = None
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
        case e: Exception => println( s"""Could not open $credentialFile due to $e""")
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
  def getCredentialsProfile(profile: String = "default")
                           (implicit ec: ExecutionContext, s: ActorSystem, m: ActorMaterializer): Future[Option[AWSPermissions]] = {
    val home = System.getProperty("user.home")
    val credentialFile = home + File.separator + ".aws" + File.separator + "credentials"
    getSpecificCredentialsProfile(credentialFile, profile)
  }

  // gets the credentials on a ec2 server for a roleName
  protected def getEC2RoleCredentials(roleName: String, timeout: Int = 500)
                                     (implicit ec: ExecutionContext, s: ActorSystem, m: ActorMaterializer): Future[Option[AWSPermissions]] = {
    val URI = s"""http://169.254.169.254/latest/meta-data/iam/security-credentials/$roleName"""
    val httpRequest = HttpRequest(method = HttpMethods.GET, uri = URI)
    val httpResponseFuture = post(httpRequest)
    val ec2Credentials = httpResponseFuture flatMap {
      case response: HttpResponse =>
        getCredentialsEC2Response(response)
    }
    Future.firstCompletedOf(
      List(ec2Credentials, after(timeout milliseconds, s.scheduler)(Future {
        None
      })))
  }

  //gets credentials from the http response of a ec2 instance
  protected def getCredentialsEC2Response(response: HttpResponse)
                                         (implicit ec: ExecutionContext, s: ActorSystem, m: ActorMaterializer): Future[Option[AWSPermissions]] = {
      response.entity.dataBytes.map(_.utf8String).grouped(Int.MaxValue).runWith(Sink.head) map {
        case responseInfo =>
          val responseData = responseInfo.mkString
          val responseJson = responseData.parseJson
          var keyId: Option[String] = None
          var accessKey: Option[String] = None
          var token: Option[String] = None
          var expiration: Option[String] = None
          val jsonMap = responseJson.asJsObject().fields
          val jsKeyId = jsonMap.get("AccessKeyId")
          if (jsKeyId.isDefined)
            keyId = Some(jsKeyId.get.toString() replaceAll("[\"]", ""))
          val jsAccessKey = jsonMap.get("SecretAccessKey")
          if (jsAccessKey.isDefined)
            accessKey = Some(jsAccessKey.get.toString() replaceAll("[\"]", ""))
          val jsToken = jsonMap.get("Token")
          if (jsToken.isDefined)
            token = Some(jsToken.get.toString() replaceAll("[\"]", ""))
          val jsExpiration = jsonMap.get("Expiration")
          if (jsExpiration.isDefined)
            expiration = Some(jsExpiration.get.toString() replaceAll("[\"]", ""))
          //TODO fix
          validCredentials(keyId, accessKey, token, expiration)
      }
  }

  // gets the role name off the ec2 instance
  protected def getAmazonEC2RoleName(timeout: Int = 500)
                                    (implicit ec: ExecutionContext, s: ActorSystem, m: ActorMaterializer): Future[Option[String]] = {
      import DefaultJsonProtocol._
      import spray.json._

      val request = HttpRequest(HttpMethods.GET, "http://169.254.169.254/latest/meta-data/iam/info")
      val instanceFuture = Http().singleRequest(request).flatMap { response =>
        response.entity.dataBytes
          .fold(ByteString.empty)(_ ++ _)
          .map(_.utf8String)
          .runWith(Sink.head)
          .map(_.parseJson)
          .map {
            _.asJsObject.fields.get("InstanceProfileArn")
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
  @throws(classOf[Exception])
  def getAmazonEC2Credentials(timeout: Int = 500)(implicit ec: ExecutionContext, s:ActorSystem, m: ActorMaterializer): Future[Option[AWSPermissions]] = {
    val roleName = getAmazonEC2RoleName(timeout)
    val ec2Credentials = roleName flatMap{
      case Some(role) =>
        getEC2RoleCredentials(role, timeout)
      case None =>
        throw new Exception("Unable to get role")
      case _ => Future{None}
    }
    ec2Credentials
  }

  /**
   * gets the aws credentials associated with the role of the ec2 instance
   * @param timeout time to wait for the ec2 response in miliseconds
   * @param ec implicit execution context
   * @param s implicit actor system
   * @param m implicit actor materializer
   * @return future aws credentials that will update automatically or None
   */
  @throws(classOf[Exception])
  def getAmazonEC2CredentialsSource(timeout:Int = 500)(implicit ec: ExecutionContext, s:ActorSystem, m: ActorMaterializer): AWSCredentialSource = {
    AWSCredentialSource(
      getAmazonEC2Credentials(timeout) map {
        case Some(permission:AWSPermissions) =>
          permission
        case None =>
          throw new Exception("Unable to get AWS credentials")
      }
    )
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
  @throws(classOf[Exception])
  def getCredentials(profile: String = "default", credentialFile: String = "", timeout: Int = 500)
                    (implicit ec: ExecutionContext, s: ActorSystem, m: ActorMaterializer): AWSCredentialSource = {
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

    AWSCredentialSource(
      futureList(credentialProviderList) map {
        case Some(cred:AWSPermissions) =>
          cred
        case None =>
          throw new Exception("Unable to get AWS credentials")
      }
    )
  }

//<<<<<<< HEAD
//  //returns the first valid credentials in the list
//  private def futureList(futureSeq: List[Future[Option[AWSPermissions]]], index: Int)
//                        (implicit ec: ExecutionContext, system:ActorSystem, materializer: ActorMaterializer): Future[Option[updatingAWSPermissions]] = {
//    futureSeq(index) flatMap  {
//      case Some(result) => Future.successful(Some(result.makeFuture()))
//      case None => if (index == futureSeq.length - 1) Future.successful(None) else futureList(futureSeq, index + 1)
//=======
  private def futureList(futureSeq: List[Future[Option[AWSPermissions]]])
                        (implicit ec: ExecutionContext, system:ActorSystem, materializer: ActorMaterializer): Future[Option[AWSPermissions]] = {
    futureSeq.head flatMap  {
      case Some(result) => Future.successful(Some(result))
      case None =>
        if (futureSeq.isEmpty) Future.successful(None)
        else futureList(futureSeq.tail)
    }
  }

  // sends outgoing request
  private def post(httpRequest: HttpRequest)(implicit ec: ExecutionContext, s: ActorSystem, m: ActorMaterializer): Future[HttpResponse] = {
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
  // formatting based on convention for amazon signing
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
