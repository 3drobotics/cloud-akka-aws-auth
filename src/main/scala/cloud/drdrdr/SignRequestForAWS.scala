package cloud.drdrdr

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.{Date, SimpleTimeZone}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import cloud.drdrdr.utils.{AWSCredentialSource, AWSPermissions}
import org.apache.commons.codec.binary.Hex

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by Jason Martens <jason.martens@3dr.com> on 10/12/15.
  *
  */

class SignRequestForAWS(implicit ec: ExecutionContext, system: ActorSystem, materializer: ActorMaterializer) {

  /**
    * uses an updatingAWSPermission to sign your requests
    *
    * @param httpRequest      the original requests without the authorization header
    * @param region           the region you are using for the aws service
    * @param service          the aws service the request is being sent to
    * @param credentialSource AWSCredentialSource that contains your AWSPermissions
    * @return the httpRequest with the authorization header added
    */
  def addAuthorizationHeaderFromCredentialSource(httpRequest: HttpRequest, region: String, service: String, credentialSource: AWSCredentialSource): Future[HttpRequest] = {
    credentialSource.getCredentials.flatMap {
      case p: AWSPermissions =>
        addAuthorizationHeader(httpRequest, p.secretAccessKey, region, p.accessKeyId, service, p.token)
    }
  }

  /**
    * adds the authorizations header to an httpRequest as described in http://docs.aws.amazon.com/general/latest/gr/sigv4_signing.html
    * // in http://docs.aws.amazon.com/general/latest/gr/sigv4_signing.html
    *
    * @param httpRequest  the original requests without the authorization header
    * @param key          an aws secret key
    * @param region       the region you are using for the aws service
    * @param accessKeyId  the access key id associated with the aws secret key
    * @param service      the aws service the request is being sent to
    * @param token        the security token associated with the temporary aws credentials (not needed if the aws credentials are not temporary)
    * @return the httpRequest with the authorization header added
    */
  def addAuthorizationHeader(httpRequest: HttpRequest, key: String, region: String, accessKeyId: String, service: String, token: String = ""): Future[HttpRequest] = {
    var tokenRequest = httpRequest
    if (token.length() > 0) {
      tokenRequest = httpRequest.withHeaders(httpRequest.headers :+ RawHeader("x-amz-security-token", token))
    }

    val request = tokenRequest.withHeaders(tokenRequest.headers :+ RawHeader("x-amz-date", getUTCTime()))
      .withUri(httpRequest.uri)
    val authHeaderFuture = createAuthorizationHeader(request, key, region, accessKeyId, service)
    authHeaderFuture.map(authHeader =>
      request.withHeaders(request.headers :+ RawHeader("Authorization", authHeader)))
  }

  // creates the authorization header as described in http://docs.aws.amazon.com/general/latest/gr/sigv4_signing.html
  protected def createAuthorizationHeader(httpRequest: HttpRequest, key: String, region: String, accessKeyId: String, service: String): Future[String] = {
    val noHmsDate = getNoHmsDate(httpRequest)
    val canonicalRequestFuture: Future[String] = createCanonicalRequest(httpRequest)
    canonicalRequestFuture.map { canonicalRequest =>
      val stringToSign = createStringToSign(httpRequest, canonicalRequest, region, service)
      val signatureKey = getSignatureKey(key, noHmsDate, region, service)
      val signature = getSignature(signatureKey, stringToSign)
      val credential = accessKeyId.trim + "/" + getCredentialScope(noHmsDate, region, service)
      val signedHeaders = getSignedHeaders(httpRequest.headers, httpRequest.uri)
      val algorithm = "AWS4-HMAC-SHA256"
      algorithm + " Credential=" + credential + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature
    }
  }

  /**
    * adds the authorization query to the uri for aws services as described in http://docs.aws.amazon.com/general/latest/gr/sigv4_signing.html
    * // in http://docs.aws.amazon.com/general/latest/gr/sigv4_signing.html
    *
    * @param httpRequest  the original requests without the authorization query
    * @param key          an aws secret key
    * @param region       the region you are using for the aws service
    * @param accessKeyId  the access key id associated with the aws secret key
    * @param service      the aws service the request is being sent to
    * @param token        the security token associated with the temporary aws credentials (not needed if the aws credentials are not temporary)
    * @return the httpRequest with the authorization query added
    */
  def addQueryString(httpRequest: HttpRequest, key: String, region: String, accessKeyId: String, service: String, expires: Int, token: String = ""): Future[HttpRequest] = {
    val date = getUTCTime()
    var tokenRequest = httpRequest
    if (token.length > 0) {
      tokenRequest = httpRequest.withHeaders(httpRequest.headers :+ RawHeader("x-amz-security-token", token))
    }
    val datedRequest = tokenRequest.withHeaders(tokenRequest.headers :+ RawHeader("x-amz-date", date))
    val noHmsDate = getNoHmsDate(datedRequest)
    val algorithm = "AWS4-HMAC-SHA256"
    val credential = accessKeyId + "/" + getCredentialScope(noHmsDate, region, service)
    val signedHeaders = getSignedHeaders(datedRequest.headers, httpRequest.uri)
    val query = uriQuery(datedRequest, date, algorithm, credential, signedHeaders, expires, token)
    val tempRequest = datedRequest.withUri(httpRequest.uri.withQuery(query))
    val canonicalRequestFuture: Future[String] = createCanonicalRequest(tempRequest)
    canonicalRequestFuture.map { canonicalRequest =>
      val stringToSign = createStringToSign(tempRequest, canonicalRequest, region, service)
      val signatureKey = getSignatureKey(key, noHmsDate, region, service)
      val signature = getSignature(signatureKey, stringToSign)
      val signedQuery: Uri.Query = query.+:("X-Amz-Signature", signature)
      val URISigned = httpRequest.uri.withQuery(signedQuery)
      tempRequest.withUri(URISigned)
    }
  }

  // only gets the signed headers needed
  // needed for Authorization Header
  protected def getSignedHeaders(headers: Seq[HttpHeader], uri: Uri): String = {
    val headersAndHost = headers :+ RawHeader("host", uri.authority.host.toString())
    // headers must be sorted by lowercase name
    val headerList = headersAndHost.map { header => header.lowercaseName() -> header.value() }
      .groupBy { case (name, value) => name }
      .toList.sortBy { case (name, value) => name }
    headerList.map(_._1).mkString(";")
  }

  // creates the signature using the required fields
  protected def getSignatureKey(key: String, dateStamp: String, regionName: String, serviceName: String): Array[Byte] = {
    val kSecret: Array[Byte] = ("AWS4" + key).getBytes("UTF8")
    val kDate: Array[Byte] = HmacSHA256(dateStamp, kSecret)
    val kRegion: Array[Byte] = HmacSHA256(regionName, kDate)
    val kService: Array[Byte] = HmacSHA256(serviceName, kRegion)
    val kSigning: Array[Byte] = HmacSHA256("aws4_request", kService)
    kSigning
  }

  // gets the signature as described by http://docs.aws.amazon.com/general/latest/gr/sigv4_signing.html
  protected def getSignature(signatureKey: Array[Byte], stringToSign: String): String = {
    Hex.encodeHexString(HmacSHA256(stringToSign, signatureKey))
  }

  // hashes data using the hmacSHA256 algorithm
  protected def HmacSHA256(data: String, key: Array[Byte]): Array[Byte] = {
    val algorithm: String = "HmacSHA256"
    val mac: Mac = Mac.getInstance(algorithm)
    mac.init(new SecretKeySpec(key, algorithm))
    mac.doFinal(data.getBytes("UTF8"))
  }

  // all other amazon related headers should be in the uri
  // adds all the params and values for the query string
  // http://docs.aws.amazon.com/general/latest/gr/sigv4-create-canonical-request.html
  protected def uriQuery(httpRequest: HttpRequest, date: String, algorithm: String, credential: String, signedHeaders: String,
                         expires: Int, token: String): Query = {
    val params: Map[String, String] = httpRequest.uri.query().toMap +("X-Amz-Algorithm" -> algorithm, "X-Amz-Credential" -> credential,
      "X-Amz-Date" -> date, "X-Amz-Expires" -> expires.toString, "X-Amz-SignedHeaders" -> signedHeaders)
    if (token.isEmpty)
      Uri.Query(params)
    else
      Uri.Query(params + ("X-Amz-Security-Token" -> token))
  }

  // Generate a canonical request string as defined in
  // http://docs.aws.amazon.com/general/latest/gr/sigv4-create-canonical-request.html
  protected def createCanonicalRequest(httpRequest: HttpRequest): Future[String] = {
    val contentsFuture =
      if (httpRequest.entity.isKnownEmpty()) Future.successful("")
      else httpRequest.entity.dataBytes.map(_.utf8String).runWith(Sink.head)
    contentsFuture.map { entity =>
      s"""${httpRequest.method.name}
         |${httpRequest.uri.path.toString}
         |${generateValidUriQuery(httpRequest.uri.query())}
         |${generateCanonicalHeaders(httpRequest.headers :+ RawHeader("host", httpRequest.uri.authority.host.toString))}
         |${signPayload(entity)}""".stripMargin
    }
  }

  // Generate a canonical string representation of the request headers
  protected def generateCanonicalHeaders(headers: Seq[HttpHeader]): String = {
    require(headers.map(_.lowercaseName()).toList.contains("host"))
    // headers must be sorted by lowercase name, and whitespace must be trimmed from the value
    val headerList = headers.map { header => header.lowercaseName() -> header.value().trim }
      .map { case (name, value) =>
        if (value.startsWith("\"") && value.endsWith("\""))
          (name, value)
        else
          (name, value.replaceAll("\\s+", " "))
      }
      // Duplicate headers need to be merged into a comma-separated list
      .groupBy { case (name, value) => name }
      .map { case (key, values) => (key, values.map(_._2).mkString(",")) }
      .toList.sortBy { case (name, values) => name }
    val headerString = headerList
      .map { case (name, values) => s"$name:$values" }
      .mkString("\n") + "\n\n"

    // A list of headers which are included in the signing process
    val signedHeaderString = headerList.map(_._1).mkString(";")

    // Return the headers followed by the signedHeaderList
    headerString + signedHeaderString
  }

  // SHA hashes the payload
  protected def signPayload(payload: String): String = {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(payload.getBytes("UTF-8"))
    Hex.encodeHexString(md.digest())
  }

  // Akka URI encoding replaces spaces with +, but Amazon needs %20
  // Akka does not encode / in query string, but Amazon needs %2F
  // Could FAIL on + in query string
  protected def generateValidUriQuery(query: Query): String = {
    query.toString.split('&').sorted.mkString("&").replace("+", "%20").replace("/", "%2F")
  }

  // creates the string to sign based on aws protocol
  protected def createStringToSign(httpRequest: HttpRequest, canonicalRequest: String, region: String, service: String): String = {
    val noHmsDate = getNoHmsDate(httpRequest)
    s"""${"AWS4-HMAC-SHA256"}
       |${getCanonicalFormDate(httpRequest.headers)}
       |${getCredentialScope(noHmsDate, region, service)}
       |${hashCanonicalRequest(canonicalRequest)}""".stripMargin
  }

  // get canonical date with time stamp
  protected def getCanonicalFormDate(headers: Seq[HttpHeader]): String = {
    val dateString = headers
      .filter(_.lowercaseName() == "x-amz-date")
      .map { header => header.lowercaseName() -> header.value().trim }
      .map { case (name, values) => s"$values" }
      .mkString("\n")
    // how to choose something else if dateString is empty
    if (dateString.isEmpty) {
      getUTCTime()
    } else {
      dateString
    }
  }

  // runs same algorithm as sign payload but on the canonicalRequest
  protected def hashCanonicalRequest(canonicalRequest: String): String = {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(canonicalRequest.getBytes("UTF-8"))
    Hex.encodeHexString(md.digest())
  }

  // got utc time for amz date from http://stackoverflow.com/questions/25991892/how-do-i-format-time-to-utc-time-zone
  // got formatting from http://stackoverflow.com/questions/5377790/date-conversion
  // formatting based on convention for amz signing
  protected def getUTCTime(): String = {
    val date = new Date()
    val format1 = new SimpleDateFormat("yyyyMMdd")
    format1.setTimeZone(new SimpleTimeZone(SimpleTimeZone.UTC_TIME, "UTC"))
    val format2 = new SimpleDateFormat("HHmmss")
    format2.setTimeZone(new SimpleTimeZone(SimpleTimeZone.UTC_TIME, "UTC"))
    format1.format(date) + "T" + format2.format(date) + "Z"
  }

  // finds the relevant fields in the http request and returns the credential scope
  protected def getCredentialScope(noHmsDate: String, region: String, service: String): String = {
    noHmsDate + "/" + region + "/" + service + "/" + "aws4_request"
  }

  // gets the date from the  x-amz-date header without HHmmss
  protected def getNoHmsDate(httpRequest: HttpRequest): String = {
    httpRequest.headers
      .filter(_.lowercaseName() == "x-amz-date")
      .map { header => header.lowercaseName() -> header.value().trim }
      .map { case (name, values) => s"$values".split("T")(0) }
      .mkString("")
  }
}

