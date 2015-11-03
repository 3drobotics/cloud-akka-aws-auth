import java.util.UUID

import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{MediaTypes, HttpEntity, HttpMethods, HttpRequest}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.util.Timeout
import io.dronekit.cloud.SignRequestForAWS
import io.dronekit.cloud.utils.Config.awsConfig
import org.scalatest._
import scala.concurrent.duration._
import spray.json._

import scala.concurrent.{Await, ExecutionContext}

/**
 * Created by Jason Martens <jason.martens@3drobotics.com> on 10/12/15.
 *
 */
class AWSKeySigningSpec extends FunSpec with Matchers {
  implicit val testSystem = akka.actor.ActorSystem("test-system")
  implicit val ec: ExecutionContext = testSystem.dispatcher
  implicit val materializer = ActorMaterializer()
//  implicit val timeout = Timeout(5 seconds)

  describe("SignRequestForAWS") {
    it("should be able to sign payloads using SHA-256") {
      val payload = "Action=ListUsers&Version=2010-05-08"
      val hashedPayload = SignRequestForAWS.signPayload(payload)
      hashedPayload shouldBe "b6359072c78d70ebee1e81adcbab4f01bf2c23245fa365ef83fe8f1f955085e2"
      println(SignRequestForAWS.signPayload("UserName=NewUser&Action=CreateUser&Version=2010-05-08"))
    }

    it("Should generate a canonical header representation") {
      val sampleHeaders = Seq(
        RawHeader("host", "iam.amazonaws.com"),
        RawHeader("Content-type", "application/x-www-form-urlencoded; charset=utf-8"),
        RawHeader("x-amz-date", "20120228T030031Z"),
        RawHeader("My-Header2", """    "a   b   c""""),
        RawHeader("My-header1", "    a   b   c ")
      )
      val canonicalForm = """content-type:application/x-www-form-urlencoded; charset=utf-8
                            |host:iam.amazonaws.com
                            |my-header1:a b c
                            |my-header2:"a   b   c"
                            |x-amz-date:20120228T030031Z
                            |
                            |content-type;host;my-header1;my-header2;x-amz-date""".stripMargin.replaceAll("\r", "")
      val testString = SignRequestForAWS.generateCanonicalHeaders(sampleHeaders)
      println(testString == canonicalForm)

      testString shouldBe canonicalForm
    }

    it("Should correctly sign a sample HTTP request") {
      val entity = HttpEntity("UserName=NewUser&Action=CreateUser&Version=2010-05-08")
      val request = HttpRequest(
        method = HttpMethods.POST,
        uri = "https://iam.amazonaws.com/authenticate+/?Param=%20with%20space&answer&Action=ListUsers",
        entity = entity,
        headers = List(
          RawHeader("Content-Type", "application/x-www-form-urlencoded; charset=utf-8"),
          RawHeader("X-Amz-Date", "20110909T233600Z"))
      )
      val testForm = Await.result(SignRequestForAWS.createCanonicalRequest(request), 10 seconds)
      val canonicalForm = """POST
                            |/authenticate%20/
                            |Action=ListUsers&Param=%20with%20space&answer
                            |content-type:application/x-www-form-urlencoded; charset=utf-8
                            |host:iam.amazonaws.com
                            |x-amz-date:20110909T233600Z
                            |
                            |content-type;host;x-amz-date
                            |5452b78ac0196aa80feaf208a7893de87f06618d602fa25024832230a4b34c53""".stripMargin.replaceAll("\r", "")
//       Check to make sure that the above is the right format especially with the \n at the end
      println(testForm)
      println(canonicalForm)

      testForm shouldBe canonicalForm
    }

    it("Should correctly create a String to Sign for a HTTP request") {
      val entity = HttpEntity("Action=ListUsers&Version=2010-05-08")
      val request = HttpRequest(
        method = HttpMethods.POST,
        uri = "https://iam.amazonaws.com/",
        entity = entity,
        headers = List(
          RawHeader("Content-Type", "application/x-www-form-urlencoded; charset=utf-8"),
          RawHeader("X-Amz-Date", "20110909T233600Z"))
      )
      val testRequest = Await.result(SignRequestForAWS.createCanonicalRequest(request), 10 seconds)
      val testForm = SignRequestForAWS.createStringToSign(request, testRequest,"us-east-1", "iam")
      val canonicalForm =
        """AWS4-HMAC-SHA256
          |20110909T233600Z
          |20110909/us-east-1/iam/aws4_request
          |3511de7e95d28ecd39e9513b642aee07e54f4941150d8df8bf94b328ef7e55e2""".stripMargin.replaceAll("\r", "")

      testForm shouldBe canonicalForm
    }

    it("Should correctly create a signing key") {
      val kSecret = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
      val testSignKey = SignRequestForAWS.getSignatureKey(kSecret, "20110909", "us-east-1", "iam")
      val SignKey: Array[Byte] = Array(152, 241, 216, 137, 254, 196, 244, 66, 26, 220, 82, 43, 171,
                                        12, 225, 248, 46, 105, 41, 194, 98, 237, 21, 229, 169, 76, 144,
                                        239, 209, 227, 176, 231).map{value => value.toByte}
      testSignKey shouldBe SignKey
    }
  }

    it("Should correctly create a signature") {
      val entity = HttpEntity("Action=ListUsers&Version=2010-05-08")
      val request = HttpRequest(
        method = HttpMethods.POST,
        uri = "https://iam.amazonaws.com/",
        entity = entity,
        headers = List(
          RawHeader("Content-Type", "application/x-www-form-urlencoded; charset=utf-8"),
          RawHeader("X-Amz-Date", "20110909T233600Z"))
      )
      val testRequest = Await.result(SignRequestForAWS.createCanonicalRequest(request), 10 seconds)
      val testForm = SignRequestForAWS.createStringToSign(request, testRequest,"us-east-1", "iam")
      val kSecret = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
      val testSignKey = SignRequestForAWS.getSignatureKey(kSecret, "20110909", "us-east-1", "iam")
      val testSignature = SignRequestForAWS.getSignature(testSignKey, testForm)
      val signature = "ced6826de92d2bdeed8f846f0bf508e8559e98e4b0199114b84c54174deb456c"
      testSignature shouldBe signature
    }

    it("Should return the correct Authorization Header"){
      val entity = HttpEntity("Action=ListUsers&Version=2010-05-08")
      val request = HttpRequest(
        method = HttpMethods.POST,
        uri = "https://iam.amazonaws.com/",
        entity = entity,
        headers = List(
          RawHeader("Content-Type", "application/x-www-form-urlencoded; charset=utf-8"),
          RawHeader("X-Amz-Date", "20110909T233600Z"))
      )
      val kSecret = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
      val region = "us-east-1"
      val accessKeyID = "AKIDEXAMPLE"
      val testAuthorization = Await.result(SignRequestForAWS.createAuthorizationHeader(request, kSecret, region, accessKeyID, "iam"), 10 seconds)
      val  authorization = "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20110909/us-east-1/iam/aws4_request, SignedHeaders=content-type;host;x-amz-date, Signature=ced6826de92d2bdeed8f846f0bf508e8559e98e4b0199114b84c54174deb456c"
      testAuthorization shouldBe authorization
    }
  val uuid = UUID.randomUUID().toString
    //uses a random UUID to prevent using the same index again
    it("Should post the correct httpRequest with all the necessary aws authentication") {
      import DefaultJsonProtocol._
      val accessKeyID = awsConfig.getString("accessKeyID")
      val region = awsConfig.getString("region")
      val kSecret = awsConfig.getString("kSecret")
      val baseURI = awsConfig.getString("testURI")
      val URI = s"${baseURI}test/$uuid"
      val data = Map("item1"->"1", "item2"->"2", "item3"->"3")
      val paramStr2 = data.toJson.compactPrint
      val entity = HttpEntity(contentType = MediaTypes.`application/x-www-form-urlencoded`, paramStr2)
      println(paramStr2)
      val request = HttpRequest(
        method = HttpMethods.POST,
        uri = URI,
        entity = entity
      )
      val authRequest = Await.result(SignRequestForAWS.addAuthorizationHeader(request, kSecret, region, accessKeyID, "es"), 10 seconds)
      println(authRequest)
      val response = Await.result(SignRequestForAWS.post(authRequest), 10 seconds)
      val responseData =  Await.result(response.entity.dataBytes.map(_.utf8String).grouped(Int.MaxValue).runWith(Sink.head), 10 seconds).mkString
      val responseJson = responseData.toJson
      println(responseJson.prettyPrint.replace("\\\\", "\\").replace("\\n", "\n"))
      val testForm = Await.result(SignRequestForAWS.createCanonicalRequest(request), 10 seconds)
      println(testForm)
      response.status.toString shouldBe "201 Created"
    }

    it ("should be a sucessful get request of the posted information") {
      import DefaultJsonProtocol._
      val accessKeyID = awsConfig.getString("accessKeyID")
      val region = awsConfig.getString("region")
      val kSecret = awsConfig.getString("kSecret")
      val baseURI = awsConfig.getString("testURI")
      val URI = s"${baseURI}test"
      val request = HttpRequest(
        method = HttpMethods.GET,
        uri = URI,
        headers = List()
      )
      val authRequest = Await.result(SignRequestForAWS.addAuthorizationHeader(request, kSecret, region, accessKeyID, "es"), 15 seconds)
      println(authRequest)
      val response = Await.result(SignRequestForAWS.post(authRequest), 10 seconds)
      val responseData =  Await.result(response.entity.dataBytes.map(_.utf8String).grouped(Int.MaxValue).runWith(Sink.head), 10 seconds).mkString
      val responseJson = responseData.toJson
      println(responseJson.prettyPrint.replace("\\\\", "\\").replace("\\n", "\n"))
      response.status.toString shouldBe "200 OK"
    }

    it ("should be a sucessful get request by adding the authorization header") {
      import DefaultJsonProtocol._
      val accessKeyID = awsConfig.getString("accessKeyID")
      val region = awsConfig.getString("region")
      val kSecret = awsConfig.getString("kSecret")
      val baseURI = awsConfig.getString("testURI")
      val URI = s"${baseURI}?Version=2013-10-15&Action=DescribeRegions"
      val request = HttpRequest(
        method = HttpMethods.GET,
        uri = URI,
        headers = List()
      )
      val authRequest = Await.result(SignRequestForAWS.addAuthorizationHeader(request, kSecret, region, accessKeyID, "es"), 15 seconds)
      println(authRequest)
      val response = Await.result(SignRequestForAWS.post(authRequest), 10 seconds)
      val responseData =  Await.result(response.entity.dataBytes.map(_.utf8String).grouped(Int.MaxValue).runWith(Sink.head), 10 seconds).mkString
      val responseJson = responseData.toJson
      println(responseJson.prettyPrint)
      response.status.toString shouldBe "200 OK"
    }

  it ("should be a sucessful get request adding the authorization query String") {
    import DefaultJsonProtocol._
    val accessKeyID = awsConfig.getString("accessKeyID")
    val region = awsConfig.getString("region")
    val kSecret = awsConfig.getString("kSecret")
    val baseURI = awsConfig.getString("testURI")
    val URI = s"${baseURI}_plugin/kibana/"
    val request = HttpRequest(
      method = HttpMethods.GET,
      uri = URI,
      headers = List()
    )
    val authRequest = Await.result(SignRequestForAWS.addQueryString(request, kSecret, region, accessKeyID, "es", 30), 15 seconds)
    println(authRequest.uri)
    val response = Await.result(SignRequestForAWS.post(authRequest), 10 seconds)
    val responseData =  Await.result(response.entity.dataBytes.map(_.utf8String).grouped(Int.MaxValue).runWith(Sink.head), 10 seconds).mkString
    val responseJson = responseData.toJson
    println(responseJson.prettyPrint.replace("\\\\", "\\").replace("\\n", "\n"))
    response.status.toString shouldBe "200 OK"
  }
}
