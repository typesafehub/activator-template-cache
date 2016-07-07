package activator

import java.util.Properties

class OptionalProperty(name: String, properties: Properties) {
  def value: Option[String] = Option(properties.getProperty(name))
  def value_=(value: Option[String]): Unit = {
    if (properties.containsKey(name)) properties.remove(name)
    value.foreach(properties.setProperty(name, _))
  }
}

object OptionalProperty {
  def apply(name: String, properties: Properties) =
    new OptionalProperty(name, properties)
}
