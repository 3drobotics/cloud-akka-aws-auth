import akka.stream.ActorMaterializer
import org.scalatest.{Matchers, FunSpec}

import io.dronekit.cloud.utils.AWSCredentials
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

/**
 * Created by Adam Villaflor on 12/11/2015.
 */
class AWSCredentialsSpec extends FunSpec with Matchers {
  implicit val testSystem = akka.actor.ActorSystem("test-system")
  implicit val ec: ExecutionContext = testSystem.dispatcher
  implicit val materializer = ActorMaterializer()
  //  implicit val timeout = Timeout(5 seconds)
  describe("Should get the credentials") {
//    it ("using environment variables") {
//      val credentials = AWSCredentials.get_envCredentials()
//
//    }
//    it ("using alternate environment variables") {
//      val credentials = AWSCredentials.get_envCredentials_alt()
//    }
//    it ("using java system variables") {
//      val credentials = AWSCredentials.get_javaSysCredentials()
//    }
//    it ("using default profile in ~/.aws/credentials") {
//      val  testPermission2 = Some(AWSCredentials.AWSPermissions("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"))
//      val futureCredentials2 = AWSCredentials.get_credentials_profile()
//      val credentials2 = Await.result(futureCredentials2, 10 seconds)
//      credentials2 shouldBe testPermission2wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKE2
//    }
//    it ("from the ec2 metadata") {
//      val credentials = AWSCredentials.get_Amazon_EC2_metadata_credentials("sys")
//    }
//  }
//  describe("Should get the credentials in the right order") {
      it ("using specific profile in ~/.aws/credentials") {
        val  testPermission = Some(AWSCredentials.AWSPermissions("AKIAIOSFODNN7EXAMPL2", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKE2"))
        val futureCredentials = AWSCredentials.get_credentials_profile("mav")
        val credentials = Await.result(futureCredentials, 10 seconds)
        credentials shouldBe testPermission
      }
      it ("using default profile in ~/.aws/credentials") {
        val futureCredentials2 = AWSCredentials.get_credentials_profile()
        val credentials2 = Await.result(futureCredentials2, 10 seconds)
        credentials2.isEmpty shouldBe false
      }
      it ("by going through the whole chain without failing") {
        val futureCredentials2 = AWSCredentials.get_credentials()
        val credentials2 = Await.result(futureCredentials2, 10 seconds)
        credentials2.isEmpty shouldBe false
      }
  }
}
