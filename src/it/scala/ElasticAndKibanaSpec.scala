import java.util.UUID

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.StatusCode._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import io.dronekit.cloud.SignRequestForAWS
import io.dronekit.cloud.utils.Config.awsConfig
import org.scalatest._
import spray.json._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

/**
 * Created by Adam Villaflor <adam.villaflor@3drobotics.com> on 11/11/15.
 *
 */
class ElasticAndKibanaSpec extends FunSpec with Matchers {
  implicit val testSystem = akka.actor.ActorSystem("test-system")
  implicit val ec: ExecutionContext = testSystem.dispatcher
  implicit val materializer = ActorMaterializer()

  //helps for debugging
  def jsonPrint(response: HttpResponse) {
    import DefaultJsonProtocol._
    val responseData =  Await.result(response.entity.dataBytes.map(_.utf8String).grouped(Int.MaxValue).runWith(Sink.head), 10 seconds).mkString
    val responseJson = responseData.toJson
    println(responseJson.prettyPrint.replace("\\\\", "\\").replace("\\n", "\n"))
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
    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = URI,
      entity = entity
    )
    val authRequest = Await.result(SignRequestForAWS.addAuthorizationHeader(request, kSecret, region, accessKeyID, "es"), 10 seconds)
    val response = Await.result(SignRequestForAWS.post(authRequest), 10 seconds)
    response.status shouldBe StatusCodes.Created
  }

  it ("should be a sucessful get request by adding the authorization header") {
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
    val response = Await.result(SignRequestForAWS.post(authRequest), 10 seconds)
    response.status shouldBe StatusCodes.OK
  }
  it ("should be a sucessful get request of the posted information") {
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
    val response = Await.result(SignRequestForAWS.post(authRequest), 10 seconds)
    response.status shouldBe StatusCodes.OK
  }

  it ("should be a sucessful get request adding the authorization query String") {
    val accessKeyID = awsConfig.getString("accessKeyID")
    val region = awsConfig.getString("region")
    val kSecret = awsConfig.getString("kSecret")
    val baseURI = awsConfig.getString("testURI")
    val URI = s"$baseURI?Version=2013-10-15&Action=DescribeRegions"
    val request = HttpRequest(
      method = HttpMethods.GET,
      uri = URI,
      headers = List()
    )
    val authRequest = Await.result(SignRequestForAWS.addQueryString(request, kSecret, region, accessKeyID, "es", 30), 15 seconds)
    val response = Await.result(SignRequestForAWS.post(authRequest), 10 seconds)
    response.status shouldBe StatusCodes.OK
  }
}
