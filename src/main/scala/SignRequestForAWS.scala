import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import _root_.akka.http.scaladsl.model.HttpHeader
import _root_.akka.http.scaladsl.model.HttpRequest
import _root_.akka.stream.scaladsl.Sink
import akka.http.scaladsl.model.headers.RawHeader
import akka.stream.ActorMaterializer
import org.apache.commons.codec.binary.Hex
import scala.concurrent.{ExecutionContext, Future}

/**
 * Created by jasonmartens on 10/12/15.
 *
 */
object SignRequestForAWS {

  /**
   * Generate a canonical string representation of the request headers
   * @param headers List of headers to include
   * @return
   */
  def generateCanonicalHeaders(headers: Seq[HttpHeader]): String = {
    require(headers.map(_.lowercaseName()).toList.contains("host"))
    // headers must be sorted by lowercase name, and whitespace must be trimmed from the value
    val headerList = headers.map{header => header.lowercaseName() -> header.value().trim}
      .map{case (name, value) =>
        if (value.startsWith("\"") && value.endsWith("\""))
         (name, value)
        else
         (name, value.replaceAll("\\s+", " "))
      }
      // Duplicate headers need to be merged into a comma-separated list
      .groupBy{case (name, value) => name}
      .map{case (key, values) => (key, values.map(_._2).mkString(","))}
      .toList.sortBy{case (name, values) => name}
    val headerString = headerList
      .map{case (name, values) => s"$name:$values"}
      .mkString("\n") + "\n\n"

    // A list of headers which are included in the signing process
    val signedHeaderString = headerList.map(_._1).mkString(";")

    // Return the headers followed by the signedHeaderList
    headerString + signedHeaderString
  }

  @throws(classOf[Exception])
  def HmacSHA256(data: String, key: Array[Byte]): Array[Byte] = {
    val algorithm: String = "HmacSHA256"
    val mac: Mac = Mac.getInstance(algorithm)
    mac.init(new SecretKeySpec(key, algorithm))
    mac.doFinal(data.getBytes("UTF8"))
  }

  @throws(classOf[Exception])
  def getSignatureKey(key: String, dateStamp: String, regionName: String, serviceName: String): Array[Byte] = {
    val kSecret: Array[Byte] = ("AWS4" + key).getBytes("UTF8")
    val kDate: Array[Byte] = HmacSHA256(dateStamp, kSecret)
    val kRegion: Array[Byte] = HmacSHA256(regionName, kDate)
    val kService: Array[Byte] = HmacSHA256(serviceName, kRegion)
    val kSigning: Array[Byte] = HmacSHA256("aws4_request", kService)
    kSigning
  }

  def signPayload(payload: String): String = {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(payload.getBytes("UTF-8"))
    Hex.encodeHexString(md.digest())
  }

  /**
   * Generate a canonical request string as defined in
   * http://docs.aws.amazon.com/general/latest/gr/sigv4-create-canonical-request.html
   * @param httpRequest The request to generate the string from
   * @return A canonical string representation of the request to be used for signing
   */
  def createCanonicalRequest(httpRequest: HttpRequest)
                            (implicit m: ActorMaterializer, ex: ExecutionContext): Future[String] = {
    // Hmmm, do we need to return a new HttpRequest?
    val contentsFuture =
      if (httpRequest.entity.isKnownEmpty()) Future.successful("")
      else httpRequest.entity.dataBytes.map(_.utf8String).runWith(Sink.head)
    contentsFuture.map { entity =>
      // TODO: Query string must be URI-encoded
      s"""${httpRequest.method.name}
         |${httpRequest.uri.toRelative}
         |${httpRequest.uri.query}
         |${generateCanonicalHeaders(httpRequest.headers :+ RawHeader("host", httpRequest.uri.authority.host.toString()))}
         |${signPayload(entity)}
         |""".stripMargin
    }
  }
}
