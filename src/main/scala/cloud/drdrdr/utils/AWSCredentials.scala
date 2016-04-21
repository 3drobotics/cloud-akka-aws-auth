package cloud.drdrdr.utils

/**
  * Created by Adam Villaflor on 11/30/2015.
  *
  */


import java.io.File
import java.text.SimpleDateFormat
import java.util.{Date, SimpleTimeZone}

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.pattern.after
import akka.stream.scaladsl.Sink
import akka.stream.{ActorMaterializer, scaladsl}
import akka.util.ByteString
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory
import spray.json._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source


/** A set of permissions which can be used to access AWS resources
  *
  * @param accessKeyId     aws accessKeyId
  * @param secretAccessKey aws accessSecretKey
  * @param token           aws token associated with credentials
  * @param expiration      aws expiration of credentials
  */
case class AWSPermissions(accessKeyId: String, secretAccessKey: String, token: String = "", expiration: String = "")


/**
  * Class which refreshes the credentials provided in the case they expire (like from an EC2 instance)
  *
  * @param initialCredentials The current credentials to use
  */
class AWSCredentialSource(initialCredentials: Future[AWSPermissions], credentialProvider: AWSCredentials)
                         (implicit ec: ExecutionContext){
  private var currentCredentials: Future[AWSPermissions] = initialCredentials

  /**
    * Updates the credentials if necessary, then returns the credentials
    *
    * @return AWSPermissions with most recent credentials
    */
  def getCredentials: Future[AWSPermissions] = {
    isExpired.flatMap{ expired =>
      if (expired) updateCredentials()
      else currentCredentials
    }
  }

  // got utc time for amz date from http://stackoverflow.com/questions/25991892/how-do-i-format-time-to-utc-time-zone
  // got formatting from http://stackoverflow.com/questions/5377790/date-conversion
  // formatting based on convention for amazon signing
  private def getUTCTime: String = {
    val date = new Date()
    val format1 = new SimpleDateFormat("yyyy-MM-dd")
    format1.setTimeZone(new SimpleTimeZone(SimpleTimeZone.UTC_TIME, "UTC"))
    val format2 = new SimpleDateFormat("HH:mm:ss")
    format2.setTimeZone(new SimpleTimeZone(SimpleTimeZone.UTC_TIME, "UTC"))
    format1.format(date) + "T" + format2.format(date) + "Z"
  }

  // checks if current credentials have expired
  private def isExpired: Future[Boolean] = {
    currentCredentials.map{ c =>
      if (c.expiration.isEmpty)
        false
      else
        c.expiration <= getUTCTime
    }
  }

  // updates the credentials from an EC2 instance if necessary
  private def updateCredentials(): Future[AWSPermissions] = {
    currentCredentials = credentialProvider.getAmazonEC2Credentials().map{
      case Some(c) => c
      case None => throw new Exception("Unable to update credentials")
    }
    currentCredentials
  }
}


class AWSCredentials(implicit ec: ExecutionContext, system: ActorSystem, materializer: ActorMaterializer) {

  /**
    * gets the credentials from the environment fields AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY
    *
    * @return aws credentials or none
    */
  def getEnvironmentCredentials: Option[AWSPermissions] = {
    getCredentialsFromMap(sys.env, "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY")
  }

  /**
    * gets the credentials from the environment fields AWS_ACCESS_KEY and AWS_SECRET_KEY
    *
    * @return aws credentials or none
    */
  def getEnvironmentAlternateCredentials: Option[AWSPermissions] = {
    getCredentialsFromMap(sys.env, "AWS_ACCESS_KEY", "AWS_SECRET_KEY")
  }

  /**
    * gets the credentials from the java system fields aws.accessKeyId and aws.secretKey
    *
    * @return aws credentials or none
    */
  def getJavaSystemCredentials: Option[AWSPermissions] = {
    val keyId = Some(System.getProperty("aws.accessKeyId"))
    val accessKey = Some(System.getProperty("aws.secretKey"))
    validCredentials(keyId, accessKey)
  }

  /**
    * gets the credentials from a specific profile in a specified file
    *
    * @param credentialFile file with aws credentials
    * @param profile        the name of the profile for the credentials to be used
    * @return aws credentials or None
    */
  def getSpecificCredentialsProfile(credentialFile: String, profile: String = "default"): Future[Option[AWSPermissions]] = {
    val header = """\s*\[([^]]*)\]\s*""".r
    val keyValue = """\s*([^=]*)=(.*)""".r
    var accessKey: Option[String] = None
    var keyId: Option[String] = None
    val log: Logger = Logger(LoggerFactory.getLogger(getClass))
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
          case e: Exception => log.error( s"""$credentialFile file does not contain $profile or is improperly formatted""")
        }
        source.close()
      } catch {
        case e: Exception => log.error( s"""Could not open $credentialFile due to $e""")
      }

      validCredentials(keyId, accessKey)
    }
  }

  /**
    * gets the credentials from a specific profile in ~/.aws/credentials
    *
    * @param profile the name of the profile for the credentials to be used
    * @return aws credentials or None
    */
  def getCredentialsProfile(profile: String = "default"): Future[Option[AWSPermissions]] = {
    val home = System.getProperty("user.home")
    val credentialFile = home + File.separator + ".aws" + File.separator + "credentials"
    getSpecificCredentialsProfile(credentialFile, profile)
  }

  /**
    * gets the aws credentials associated with the role of the ec2 instance
    *
    * @param timeout time to wait for the ec2 response in milliseconds
    * @return future aws credentials that will update automatically or None
    */
  def getAmazonEC2CredentialsSource(timeout: Int = 300): AWSCredentialSource = {
    val credentialFuture = getAmazonEC2Credentials(timeout) map {
      case Some(permission: AWSPermissions) =>
        permission
      case None =>
        throw new Exception("Unable to get AWS credentials")
    }
    new AWSCredentialSource(credentialFuture, this)
  }

  /**
    * gets the aws credentials associated with the role of the ec2 instance
    *
    * @param timeout time to wait for the ec2 response in milliseconds
    * @return aws credentials or None
    */
  def getAmazonEC2Credentials(timeout: Int = 300): Future[Option[AWSPermissions]] = {
    val roleName = getAmazonEC2RoleName(timeout)
    val ec2Credentials = roleName flatMap {
      case Some(role) =>
        getEC2RoleCredentials(role, timeout)
      case None =>
        throw new Exception("Unable to get role")
      case _ => Future {
        None
      }
    }
    ec2Credentials
  }

  // gets the credentials on a ec2 server for a roleName
  def getEC2RoleCredentials(roleName: String, timeout: Int = 300): Future[Option[AWSPermissions]] = {
    val URI = s"""http://169.254.169.254/latest/meta-data/iam/security-credentials/$roleName"""
    val httpRequest = HttpRequest(method = HttpMethods.GET, uri = URI)
    val httpResponseFuture = sendRequest(httpRequest)
    val ec2Credentials = httpResponseFuture flatMap {
      case response: HttpResponse =>
        getCredentialsEC2Response(response)
    }
    Future.firstCompletedOf(
      List(ec2Credentials, after(timeout milliseconds, system.scheduler)(Future {
        None
      })))
  }

  // gets credentials from the http response of a ec2 instance
  def getCredentialsEC2Response(response: HttpResponse): Future[Option[AWSPermissions]] = {
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
        validCredentials(keyId, accessKey, token, expiration)
    }
  }

  // checks if both the access key Id and the secret key are valid
  def validCredentials(keyId: Option[String], accessKey: Option[String], token: Option[String] = Some(""), expiration: Option[String] = Some("")): Option[AWSPermissions] = {
    if (keyId.isDefined && accessKey.isDefined && keyId.get != null && accessKey.get != null)
      Some(new AWSPermissions(keyId.get, accessKey.get, token.get, expiration.get))
    else None
  }

  // gets the role name off the ec2 instance
  protected def getAmazonEC2RoleName(timeout: Int = 300): Future[Option[String]] = {
    import spray.json._

    val request = HttpRequest(HttpMethods.GET, "http://169.254.169.254/latest/meta-data/iam/info")
    val instanceFuture = sendRequest(request).flatMap { response =>
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
      List(instanceFuture, after(timeout milliseconds, system.scheduler)(Future.successful(None))))
  }

  // sends outgoing request
  private def sendRequest(httpRequest: HttpRequest): Future[HttpResponse] = {
    val endpoint = httpRequest.uri.toString()
    val uri = java.net.URI.create(endpoint)
    val outgoingConn = if (uri.getScheme == "https") {
      Http().outgoingConnectionHttps(uri.getHost, if (uri.getPort == -1) 443 else uri.getPort)
    } else {
      Http().outgoingConnection(uri.getHost, if (uri.getPort == -1) 80 else uri.getPort)
    }
    scaladsl.Source.single(httpRequest).via(outgoingConn).runWith(Sink.head)
  }

  /**
    * gets the first aws credentials it finds by checking the environment, java system, local credential file,
    * and ec2 instance in that respective order
    * gets the credentials from a specific profile in a specified file
    *
    * @param credentialFile file with aws credentials
    * @param profile        the name of the profile for the credentials to be used
    * @param timeout        time to wait for the ec2 response in milliseconds
    * @return aws credentials or None
    */
  def getCredentials(profile: String = "default", credentialFile: Option[String] = None, timeout: Int = 300): AWSCredentialSource = {
    val envCredentials = Future.successful(getEnvironmentCredentials)
    val envCredentialsAlt = Future.successful(getEnvironmentAlternateCredentials)
    val javaSysCredentials = Future.successful(getJavaSystemCredentials)
    val profileCredentials =
      if (credentialFile.isEmpty) getCredentialsProfile(profile)
      else getSpecificCredentialsProfile(credentialFile.get, profile)
    //fix
    val ec2Credential = getAmazonEC2Credentials()
    val credentialProviderList: List[Future[Option[AWSPermissions]]] =
      List(envCredentials, envCredentialsAlt, javaSysCredentials, profileCredentials, ec2Credential)

    val credentialFuture = futureList(credentialProviderList) map {
      case Some(cred: AWSPermissions) =>
        cred
      case None =>
        throw new Exception("Unable to get AWS credentials")
    }
    new AWSCredentialSource(credentialFuture, this)
  }

  // gets credentials from a generic string to string map
  def getCredentialsFromMap(environment: Map[String, String], keyIdKey: String, accessKeyKey: String): Option[AWSPermissions] = {
    val keyId = environment.get(keyIdKey)
    val accessKey = environment.get(accessKeyKey)
    validCredentials(keyId, accessKey)
  }

  // maps the future list to the first future in the list to return successfully
  private def futureList(futureSeq: List[Future[Option[AWSPermissions]]]): Future[Option[AWSPermissions]] = {
    futureSeq.head flatMap {
      case Some(result) => Future.successful(Some(result))
      case None =>
        if (futureSeq.isEmpty) Future.successful(None)
        else futureList(futureSeq.tail)
    }
  }



}
