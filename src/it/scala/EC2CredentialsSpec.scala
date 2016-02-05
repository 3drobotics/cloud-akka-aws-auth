import akka.http.scaladsl.model.{HttpResponse, StatusCodes, HttpMethods, HttpRequest}
import akka.stream.scaladsl.Sink
import io.dronekit.cloud.SignRequestForAWS
import io.dronekit.cloud.utils.Config._
import org.scalatest.{Matchers, FunSpec}
import akka.stream.ActorMaterializer

import io.dronekit.cloud.utils.AWSCredentials
import spray.json.DefaultJsonProtocol
import spray.json._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
/**
 * Created by Adam Villaflor on 1/22/2016.
 */
class EC2CredentialsSpec extends FunSpec with Matchers{
  implicit val testSystem = akka.actor.ActorSystem("test-system")
  implicit val ec: ExecutionContext = testSystem.dispatcher
  implicit val materializer = ActorMaterializer()

  def jsonPrint(response: HttpResponse) {
    import DefaultJsonProtocol._
    val responseData =  Await.result(response.entity.dataBytes.map(_.utf8String).grouped(Int.MaxValue).runWith(Sink.head), 10 seconds).mkString
    val responseJson = responseData.parseJson
    println(responseJson.prettyPrint)
  }

  describe("Should get the credentials") {
    it ("get credentials") {
      val roleName = awsConfig.getString("roleName")
//      val URI = s"""http://169.254.169.254/latest/meta-data/iam/security-credentials/${roleName}"""
      val URI = s"""http://169.254.169.254/latest/meta-data/iam/"""
      val httpRequest = HttpRequest(method = HttpMethods.GET, uri = URI)
      val httpResponseFuture = SignRequestForAWS.post(httpRequest)
      httpResponseFuture map{
        case response:HttpResponse =>
          jsonPrint(response)
      }
      val futureCredentials = AWSCredentials.get_Amazon_EC2_metadata_credentials(roleName)
      val credentials = Await.result(futureCredentials, 10 seconds)
      credentials.isEmpty shouldBe false
    }
    it ("send a request") {
      val roleName = awsConfig.getString("roleName")
      val futureCredentials = AWSCredentials.get_Amazon_EC2_metadata_credentials(roleName)
      Await.result(futureCredentials, 10 seconds) match {
        case Some(permission) =>
          val accessKeyID = permission.accessKeyId
          val kSecret = permission.secretAccessKey
          println(accessKeyID)
          val token = permission.token
          val baseURI = awsConfig.getString("URI")
          val service = awsConfig.getString("service")
          val region = awsConfig.getString("region")
          val URI = s"${baseURI}?Version=2013-10-15&Action=DescribeRegions"
          val request = HttpRequest(
            method = HttpMethods.GET,
            uri = URI
          )
          val authRequest = Await.result(SignRequestForAWS.addAuthorizationHeader(request, kSecret, region, accessKeyID, service, token), 15 seconds)
          println(authRequest)
          val response = Await.result(SignRequestForAWS.post(authRequest), 10 seconds)
          jsonPrint(response)
          response.status shouldBe StatusCodes.OK
        case None =>
          None shouldBe StatusCodes.OK
      }
    }
    it ("send a request using general get method") {
      val roleName = awsConfig.getString("roleName")
      val futureCredentials = AWSCredentials.get_credentials(profile = "fail", roleName = roleName)
      Await.result(futureCredentials, 10 seconds) match {
        case Some(permission) =>
          val accessKeyID = permission.accessKeyId
          println(accessKeyID)
          val kSecret = permission.secretAccessKey
          println(kSecret)
          val token = permission.token
          val baseURI = awsConfig.getString("URI")
          val service = awsConfig.getString("service")
          val region = awsConfig.getString("region")
          val URI = s"${baseURI}?Version=2013-10-15&Action=DescribeRegions"
          val request = HttpRequest(
            method = HttpMethods.GET,
            uri = URI
          )
          val authRequest = Await.result(SignRequestForAWS.addAuthorizationHeader(request, kSecret, region, accessKeyID, service, token), 15 seconds)
          println(authRequest)
          val response = Await.result(SignRequestForAWS.post(authRequest), 10 seconds)
          jsonPrint(response)
          response.status shouldBe StatusCodes.OK
        case None =>
          None shouldBe StatusCodes.OK
      }
    }
  }
}
