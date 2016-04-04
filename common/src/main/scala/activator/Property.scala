package activator

import java.util.Properties

class Property(name: String, properties: Properties) {
  def value: String = properties.getProperty(name)
  def value_=(v: String): Unit = {
    properties.setProperty(name, v)
  }
}

object Property {
  def apply(name: String, properties: Properties): Property = new Property(name, properties)
}
