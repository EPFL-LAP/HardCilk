package Util

sealed trait ArchitectureMode { def cliName: String }
object ArchitectureMode {
  case object Updated extends ArchitectureMode { val cliName = "updated" }
  case object Legacy extends ArchitectureMode { val cliName = "legacy" }

  def parse(value: String): Option[ArchitectureMode] = value match {
    case "updated" => Some(Updated)
    case "legacy"  => Some(Legacy)
    case _         => None
  }
}

sealed trait ArgumentServerMode { def cliName: String }
object ArgumentServerMode {
  case object Cached extends ArgumentServerMode { val cliName = "cached" }
  case object NoCache extends ArgumentServerMode { val cliName = "no-cache" }

  def parse(value: String): Option[ArgumentServerMode] = value match {
    case "cached"   => Some(Cached)
    case "no-cache" => Some(NoCache)
    case _          => None
  }
}

/** Fully-resolved generator A/B selection. */
case class GeneratorProfile(
    architecture: ArchitectureMode,
    argumentServer: ArgumentServerMode
) {
  def isLegacy: Boolean = architecture == ArchitectureMode.Legacy
  def usesCachedArgumentServer: Boolean =
    !isLegacy && argumentServer == ArgumentServerMode.Cached

  def argumentServerImplementation: String =
    if (isLegacy) "legacy-2469686"
    else if (usesCachedArgumentServer) "updated-cached"
    else "updated-no-cache"
}

object GeneratorProfile {
  def resolve(
      architecture: String,
      requestedArgumentServer: Option[String]
  ): Either[String, GeneratorProfile] = {
    val architectureMode = ArchitectureMode.parse(architecture).toRight(
      s"unknown architecture '$architecture' (expected updated or legacy)"
    )
    val requestedMode = requestedArgumentServer match {
      case None        => Right(None)
      case Some(value) => ArgumentServerMode.parse(value).map(Some(_)).toRight(
        s"unknown argument server '$value' (expected cached or no-cache)"
      )
    }

    for {
      arch <- architectureMode
      requested <- requestedMode
      effective = requested.getOrElse(
        if (arch == ArchitectureMode.Legacy) ArgumentServerMode.NoCache
        else ArgumentServerMode.Cached
      )
      _ <- Either.cond(
        !(arch == ArchitectureMode.Legacy && effective == ArgumentServerMode.Cached),
        (),
        "legacy architecture only supports the historical no-cache argument server"
      )
    } yield GeneratorProfile(arch, effective)
  }
}
