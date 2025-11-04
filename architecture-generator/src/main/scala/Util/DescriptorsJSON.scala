package Descriptors

import io.circe._
import io.circe.parser._
import io.circe.syntax._
// Import 'extras' for default value support
import io.circe.generic.extras.semiauto._ 
import io.circe.generic.extras.Configuration
import java.io.PrintWriter

// This object provides all JSON codecs and helper functions
object DescriptorJSON {

  // --- Configuration to enable default case class parameters ---
  implicit val config: Configuration = Configuration.default.withDefaults

  // --- Explicitly provide codecs for all our local types ---
  // We use deriveConfiguredDecoder/Encoder to respect defaults.

  implicit val memSystemDecoder: Decoder[MemSystemDescriptor] = deriveConfiguredDecoder[MemSystemDescriptor]
  implicit val memSystemEncoder: Encoder[MemSystemDescriptor] = deriveConfiguredEncoder[MemSystemDescriptor]

  implicit val portDescriptorDecoder: Decoder[PortDescriptor] = deriveConfiguredDecoder[PortDescriptor]
  implicit val portDescriptorEncoder: Encoder[PortDescriptor] = deriveConfiguredEncoder[PortDescriptor]

  implicit val connectionDescriptorDecoder: Decoder[ConnectionDescriptor] = deriveConfiguredDecoder[ConnectionDescriptor]
  implicit val connectionDescriptorEncoder: Encoder[ConnectionDescriptor] = deriveConfiguredEncoder[ConnectionDescriptor]

  implicit val systemConnectionsDecoder: Decoder[SystemConnections] = deriveConfiguredDecoder[SystemConnections]
  implicit val systemConnectionsEncoder: Encoder[SystemConnections] = deriveConfiguredEncoder[SystemConnections]

  implicit val interconnectDescriptorDecoder: Decoder[InterconnectDescriptor] = deriveConfiguredDecoder[InterconnectDescriptor]
  implicit val interconnectDescriptorEncoder: Encoder[InterconnectDescriptor] = deriveConfiguredEncoder[InterconnectDescriptor]
  
  implicit val memStatsDecoder: Decoder[MemStats] = deriveConfiguredDecoder[MemStats]
  implicit val memStatsEncoder: Encoder[MemStats] = deriveConfiguredEncoder[MemStats]
  
  implicit val sideConfigDecoder: Decoder[SideConfig] = deriveConfiguredDecoder[SideConfig]
  implicit val sideConfigEncoder: Encoder[SideConfig] = deriveConfiguredEncoder[SideConfig]
  
  implicit val taskDescriptorDecoder: Decoder[TaskDescriptor] = deriveConfiguredDecoder[TaskDescriptor]
  implicit val taskDescriptorEncoder: Encoder[TaskDescriptor] = deriveConfiguredEncoder[TaskDescriptor]

  // This one will now derive correctly AND use defaults
  implicit val fullSysGenDescriptorDecoder: Decoder[FullSysGenDescriptor] = deriveConfiguredDecoder[FullSysGenDescriptor]
  implicit val fullSysGenDescriptorEncoder: Encoder[FullSysGenDescriptor] = deriveConfiguredEncoder[FullSysGenDescriptor]

  implicit val fullSysGenDescriptorExtendedDecoder: Decoder[FullSysGenDescriptorExtended] = deriveConfiguredDecoder[FullSysGenDescriptorExtended]
  implicit val fullSysGenDescriptorExtendedEncoder: Encoder[FullSysGenDescriptorExtended] = deriveConfiguredEncoder[FullSysGenDescriptorExtended]


  // --- JSON File I/O Helpers ---

  /**
   * Parses a JSON file into a case class [T].
   * Automatically uses case class default values for missing fields.
   */
  def parseJsonFile[T](fpath: String)(implicit decoder: Decoder[T]): T = {
    val rawStringJson = scala.io.Source.fromFile(fpath).mkString
    
    decode[T](rawStringJson) match {
      case Right(data) => data
      case Left(error) => 
        throw new Exception(s"Error parsing JSON file '$fpath': $error")
    }
  }

  /**
   * Dumps a case class [T] to a pretty-printed JSON file.
   */
  def dumpJsonFile[T](fpath: String, data: T)(implicit encoder: Encoder[T]): Unit = {
    val jsonString = data.asJson.spaces2 // .spaces2 for pretty-printing
    val writer = new PrintWriter(fpath)
    try {
      writer.write(jsonString)
    } finally {
      writer.close()
    }
  }
}