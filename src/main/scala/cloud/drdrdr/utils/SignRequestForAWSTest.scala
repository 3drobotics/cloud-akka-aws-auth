package cloud.drdrdr.utils

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpHeader, HttpRequest, Uri}
import akka.stream.ActorMaterializer
import cloud.drdrdr.SignRequestForAWS

import scala.concurrent.{ExecutionContext, Future}

/**
 * Created by Adam Villaflor on 4/22/2016.
 */
class SignRequestForAWSTest(implicit ec: ExecutionContext, system: ActorSystem, materializer: ActorMaterializer) extends SignRequestForAWS {

  override def getSignedHeaders(headers: Seq[HttpHeader], uri: Uri): String = {
    super.getSignedHeaders(headers,uri)
  }

  override def getSignatureKey(key: String, dateStamp: String, regionName: String, serviceName: String): Array[Byte] = {
    super.getSignatureKey(key, dateStamp, regionName, serviceName)
  }

  override def getSignature(signatureKey: Array[Byte], stringToSign: String): String = {
    super.getSignature(signatureKey, stringToSign)
  }

  override def createCanonicalRequest(httpRequest: HttpRequest): Future[String] = {
    super.createCanonicalRequest(httpRequest)
  }

  override def generateCanonicalHeaders(headers: Seq[HttpHeader]): String = {
    super.generateCanonicalHeaders(headers)
  }

  override def signPayload(payload: String): String = {
    super.signPayload(payload)
  }

  override def createStringToSign(httpRequest: HttpRequest, canonicalRequest: String, region: String, service: String): String = {
    super.createStringToSign(httpRequest, canonicalRequest, region, service)
  }

  override def createAuthorizationHeader(httpRequest: HttpRequest, key: String, region: String, accessKeyId: String, service: String): Future[String] = {
    super.createAuthorizationHeader(httpRequest, key, region, accessKeyId, service)
  }
}
