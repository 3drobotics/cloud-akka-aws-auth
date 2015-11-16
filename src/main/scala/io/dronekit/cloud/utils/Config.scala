package io.dronekit.cloud.utils

/**
 * Created by Jason Martens <jason.martens@3drobotics.com> on 7/15/15.
 *
 * Application configuration loaded from application.conf
 */
import com.typesafe.config.ConfigFactory

trait Config {
  private val config = ConfigFactory.load()
  val awsConfig = config.getConfig("aws")
}

object Config extends Config