package uy.com.netlabs.luthier.endpoint.stream

object serializers {
  type Serializer[T] = T => Array[Byte]
  
  def string: Serializer[String] = _.getBytes
  def serializable: Serializer[java.io.Serializable] = s => {
    import java.io._
    val baos = new ByteArrayOutputStream(1024)
    val out = new ObjectOutputStream(baos)
    out.writeObject(s)
    out.close()
    baos.toByteArray()
  }
}