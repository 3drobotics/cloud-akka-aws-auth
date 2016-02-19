import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.stream.ActorMaterializer
import cloud.drdrdr._
import org.scalatest._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.language.postfixOps

/**
 * Created by Jason Martens <jason.martens@3drobotics.com> on 10/12/15.
 *
 */
class AWSKeySigningSpec extends FunSpec with Matchers with SignRequestForAWS{
  implicit val testSystem = akka.actor.ActorSystem("test-system")
  implicit val ec: ExecutionContext = testSystem.dispatcher
  implicit val materializer = ActorMaterializer()
//  implicit val timeout = Timeout(5 seconds)

  describe("SignRequestForAWS") {
    it("should be able to sign payloads using SHA-256") {
      val payload = "Action=ListUsers&Version=2010-05-08"
      val hashedPayload = signPayload(payload)
      hashedPayload shouldBe "b6359072c78d70ebee1e81adcbab4f01bf2c23245fa365ef83fe8f1f955085e2"
    }

    it("Should generate a canonical header representation") {
      val sampleHeaders = Seq(
        RawHeader("host", "iam.amazonaws.com"),
        RawHeader("Content-type", "application/x-www-form-urlencoded; charset=utf-8"),
        RawHeader("x-amz-date", "20120228T030031Z"),
        RawHeader("My-Header2", """    "a   b   c""""),
        RawHeader("My-header1", "    a   b   c ")
      )
      val canonicalForm =
        """content-type:application/x-www-form-urlencoded; charset=utf-8
                            |host:iam.amazonaws.com
                            |my-header1:a b c
                            |my-header2:"a   b   c"
                            |x-amz-date:20120228T030031Z
                            |
                            |content-type;host;my-header1;my-header2;x-amz-date""".stripMargin.replaceAll("\r", "")
      val testString = generateCanonicalHeaders(sampleHeaders)
      testString shouldBe canonicalForm
    }

    it("Should correctly sign a sample HTTP request") {
      val entity = HttpEntity("UserName=NewUser&Action=CreateUser&Version=2010-05-08")
      val uri = Uri("https://iam.amazonaws.com/authenticate%20/?Param=%20with%20space&answer&Action=ListUsers")
      val request = HttpRequest(
        method = HttpMethods.POST,
        uri = uri,
        entity = entity,
        headers = List(
          RawHeader("Content-Type", "application/x-www-form-urlencoded; charset=utf-8"),
          RawHeader("X-Amz-Date", "20110909T233600Z"))
      )
      val testForm = Await.result(createCanonicalRequest(request), 10 seconds)
      val canonicalForm =
        """POST
          |/authenticate%20/
          |Action=ListUsers&Param=%20with%20space&answer
          |content-type:application/x-www-form-urlencoded; charset=utf-8
          |host:iam.amazonaws.com
          |x-amz-date:20110909T233600Z
          |
          |content-type;host;x-amz-date
          |5452b78ac0196aa80feaf208a7893de87f06618d602fa25024832230a4b34c53""".stripMargin.replaceAll("\r", "")
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
      val testRequest = Await.result(createCanonicalRequest(request), 10 seconds)
      val testForm = createStringToSign(request, testRequest,"us-east-1", "iam")
      val canonicalForm =
        """AWS4-HMAC-SHA256
          |20110909T233600Z
          |20110909/us-east-1/iam/aws4_request
          |3511de7e95d28ecd39e9513b642aee07e54f4941150d8df8bf94b328ef7e55e2""".stripMargin.replaceAll("\r", "")
      testForm shouldBe canonicalForm
    }

    it("Should correctly create a signing key") {
      val kSecret = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
      val
      testSignKey = getSignatureKey(kSecret, "20110909", "us-east-1", "iam")
      val SignKey: Array[Byte] = Array(152, 241, 216, 137, 254, 196, 244, 66, 26, 220, 82, 43, 171,
                                        12, 225, 248, 46, 105, 41, 194, 98, 237, 21, 229, 169, 76, 144,
                                        239, 209, 227, 176, 231).map{value => value.toByte}
      testSignKey shouldBe SignKey
    }

    it("Should correctly create a signature") {
      val entity = HttpEntity("Action=ListUsers&Version=2010-05-08")
      val request = HttpRequest(
        method = HttpMethods.POST,
        uri = "https://iam.amazonaws.com/", entity = entity,
        headers = List(
          RawHeader("Content-Type", "application/x-www-form-urlencoded; charset=utf-8"),
          RawHeader("X-Amz-Date", "20110909T233600Z"))
      )
      val testRequest = Await.result(createCanonicalRequest(request), 10 seconds)
      val testForm = createStringToSign(request, testRequest,"us-east-1", "iam")
      val kSecret = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
      val testSignKey = getSignatureKey(kSecret, "20110909", "us-east-1", "iam")
      val testSignature = getSignature(testSignKey, testForm)
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
      val testAuthorization = Await.result(createAuthorizationHeader(request, kSecret, region, accessKeyID, "iam"), 10 seconds)
      val  authorization = "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20110909/us-east-1/iam/aws4_request, SignedHeaders=content-type;host;x-amz-date, Signature=ced6826de92d2bdeed8f846f0bf508e8559e98e4b0199114b84c54174deb456c"
      testAuthorization shouldBe authorization
    }
  }
}
