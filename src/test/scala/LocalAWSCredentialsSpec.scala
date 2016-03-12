import java.io._
import java.util.UUID

import akka.http.scaladsl.model.{HttpEntity, HttpResponse}
import akka.stream.ActorMaterializer
import cloud.drdrdr.utils.AWSCredentials
import org.scalatest.{FunSpec, Matchers}

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._


/**
 * Created by Adam Villaflor on 2/2/2016.
 */
class LocalAWSCredentialsSpec extends FunSpec with Matchers with AWSCredentials{
  implicit val testSystem = akka.actor.ActorSystem("test-system")
  implicit val ec: ExecutionContext = testSystem.dispatcher
  implicit val materializer = ActorMaterializer()

  describe ("should get the credentials") {
    it ("should get credentials from mock environment map") {
      val map = sys.env
      val updatedMap = map + ("AWS_ACCESS_KEY_ID" -> "AKIAIOSFODNN7EXAMPLE", "AWS_SECRET_ACCESS_KEY" -> "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
      val testCredentials = Some(new AWSPermissions("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"))
      val credentials = getCredentialsFromMap(updatedMap, "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY")
      credentials.get.accessKeyId shouldBe testCredentials.get.accessKeyId
      credentials.get.secretAccessKey shouldBe testCredentials.get.secretAccessKey
      credentials.get.token shouldBe testCredentials.get.token
    }
    it ("should get credentials from alternate mock environment map") {
      val map = sys.env
      val updatedMap = map + ("AWS_ACCESS_KEY" -> "AKIAIOSFODNN7EXAMPLE", "AWS_SECRET_KEY" -> "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
      val testCredentials = Some(new AWSPermissions("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"))
      val credentials = getCredentialsFromMap(updatedMap, "AWS_ACCESS_KEY", "AWS_SECRET_KEY")
      credentials.get.accessKeyId shouldBe testCredentials.get.accessKeyId
      credentials.get.secretAccessKey shouldBe testCredentials.get.secretAccessKey
      credentials.get.token shouldBe testCredentials.get.token
    }
    it ("should get credentials from java system properties") {
      val properties = System.getProperties()
      properties.setProperty("aws.accessKeyId", "AKIAIOSFODNN7EXAMPLE")
      properties.setProperty("aws.secretKey", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
      val testCredentials = Some(new AWSPermissions("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"))
      val credentials = AWSCredentials.getJavaSystemCredentials()
      credentials.get.accessKeyId shouldBe testCredentials.get.accessKeyId
      credentials.get.secretAccessKey shouldBe testCredentials.get.secretAccessKey
      credentials.get.token shouldBe testCredentials.get.token
    }
    it ("should get credentials from a local file") {
      val home = new File(System.getProperty("user.home"))
      val uuidFile = new File(home.toPath + File.separator + UUID.randomUUID().toString + ".credentials")
      val writer = new PrintWriter(uuidFile)
      writer.write("[default]\naws_access_key_id=AKIAIOSFODNN7EXAMPLE\naws_secret_access_key=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
      writer.close()
      val futureCredentials = AWSCredentials.getSpecificCredentialsProfile(uuidFile.toString)
      val credentials = Await.result(futureCredentials, 10 seconds)
      uuidFile.delete()
      val testCredentials = Some(new AWSPermissions("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"))
      credentials.get.accessKeyId shouldBe testCredentials.get.accessKeyId
      credentials.get.secretAccessKey shouldBe testCredentials.get.secretAccessKey
      credentials.get.token shouldBe testCredentials.get.token
    }
    it ("should get credentials from httpResponse") {
      val entity = HttpEntity("{\"AccessKeyId\":\"AKIAIOSFODNN7EXAMPLE\",\n\"SecretAccessKey\":\"wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY\"\n,\"Token\":\"test\"\n,\"Expiration\":\"test\"\n }")
      val response = HttpResponse(
        entity = entity
      )
      val testCredentials = Some(new AWSPermissions("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "test"))
      val futureCredentials = getCredentialsEC2Response(response)
      val credentials = Await.result(futureCredentials, 10 seconds)
      credentials.get.accessKeyId shouldBe testCredentials.get.accessKeyId
      credentials.get.secretAccessKey shouldBe testCredentials.get.secretAccessKey
      credentials.get.token shouldBe testCredentials.get.token
    }
    it ("tests ordering of credentials") {
      val home = System.getProperty("user.home")
      val uuidFile = home + File.separator + UUID.randomUUID().toString + ".credentials"
      val f = new File(uuidFile)
      val writer = new PrintWriter(f)
      writer.write("[default]\naws_access_key_id=AKIAIOSFODNN7EXAMPLE\naws_secret_access_key=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
      writer.close()
      val credentialsSource = AWSCredentials.getCredentials(credentialFile = uuidFile)
      val credentials2 = Await.result(credentialsSource.getCredentials, 10 seconds)
      val testCredentials2 = new AWSPermissions("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")

      credentials2.accessKeyId shouldBe testCredentials2.accessKeyId
      credentials2.secretAccessKey shouldBe testCredentials2.secretAccessKey
      credentials2.token shouldBe testCredentials2.token

      val properties = System.getProperties()
      properties.setProperty("aws.accessKeyId", "AKIAIOSFODNN7EXAMPLE")
      properties.setProperty("aws.secretKey", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
      val testCredentials1 = new AWSPermissions("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
      val credentials1 = Await.result(AWSCredentials.getCredentials(credentialFile = uuidFile).getCredentials, 10 seconds)
      f.delete()
      credentials1.accessKeyId shouldBe testCredentials1.accessKeyId
      credentials1.secretAccessKey shouldBe testCredentials1.secretAccessKey
      credentials1.token shouldBe testCredentials1.token
    }
//    it ("by going through the whole chain without failing") {
//      val futureCredentials2 = AWSCredentials.getCredentials()
//      val credentials2 = Await.result(futureCredentials2, 10 seconds)
//      credentials2.isEmpty shouldBe false
//    }
  }
}
