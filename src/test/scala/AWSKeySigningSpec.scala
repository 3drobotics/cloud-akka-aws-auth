import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpEntity, HttpMethods, HttpRequest}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import org.scalatest._
import scala.concurrent.duration._

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
    }

    it("Should generate a canonical header representation") {
      val sampleHeaders = Seq(
        RawHeader("host", "iam.amazonaws.com"),
        RawHeader("Content-type", "application/x-www-form-urlencoded; charset=utf-8"),
        RawHeader("My-header1", "    a   b   c "),
        RawHeader("x-amz-date", "20120228T030031Z"),
        RawHeader("My-Header2", """    "a   b   c"""")
      )
      val canonicalForm = """content-type:application/x-www-form-urlencoded; charset=utf-8
                            |host:iam.amazonaws.com
                            |my-header1:a b c
                            |my-header2:"a   b   c"
                            |x-amz-date:20120228T030031Z
                            |content-type;host;my-header1;my-header2;x-amz-date""".stripMargin
      val testString = SignRequestForAWS.generateCanonicalHeaders(sampleHeaders)
      testString shouldBe canonicalForm
    }

    it("Should correctly sign a sample HTTP request") {
      val entity = HttpEntity("UserName=NewUser&Action=CreateUser&Version=2010-05-08")
      val request = HttpRequest(
        method = HttpMethods.POST,
        uri = "https://iam.amazonaws.com/",
        entity = entity,
        headers = List(
          RawHeader("Content-Type", "application/x-www-form-urlencoded; charset=utf-8"),
          RawHeader("X-Amz-Date", "20110909T233600Z"))
      )
      val testForm = Await.result(SignRequestForAWS.createCanonicalRequest(request), 10 seconds)
      val canonicalForm = """POST
                            |/
                            |
                            |content-type:application/x-www-form-urlencoded; charset=utf-8
                            |host:iam.amazonaws.com
                            |x-amz-date:20110909T233600Z
                            |
                            |content-type;host;x-amz-date
                            |b6359072c78d70ebee1e81adcbab4f01bf2c23245fa365ef83fe8f1f955085e2""".stripMargin
      println(testForm)
      println(canonicalForm)
      testForm shouldBe canonicalForm
    }
  }

}
