package activator
package templates
package repository

case class RepositoryException(msg: String, cause: Throwable) extends RuntimeException(msg, cause)
