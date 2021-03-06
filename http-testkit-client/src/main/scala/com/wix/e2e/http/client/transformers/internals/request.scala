package com.wix.e2e.http.client.transformers.internals

import java.io.File
import java.nio.file.Files

import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Cookie, RawHeader, `User-Agent`}
import akka.util.ByteString
import com.wix.e2e.http.api.Marshaller
import com.wix.e2e.http.client.transformers._
import com.wix.e2e.http.client.transformers.internals.RequestPartOps._
import com.wix.e2e.http.exceptions.{MultipartFileReadError, UserAgentModificationNotSupportedException}
import com.wix.e2e.http.{RequestTransformer, WixHttpTestkitResources}

import scala.util.control.Exception.handling
import scala.xml.Node
import scalaz.effect.IoExceptionOr.IoException

trait HttpClientRequestUrlTransformers {
  def withParam(param: (String, String)): RequestTransformer = withParams(param)
  def withParams(params: (String, String)*): RequestTransformer = r =>
    r.copy(uri = r.uri
      .withQuery( Query(r.uri.rawQueryString
        .map( Query(_).toSeq )
        .getOrElse(Nil)
        ++ params: _*)) )
}

trait HttpClientRequestHeadersTransformers {
  def withHeader(header: (String, String)): RequestTransformer = withHeaders(header)
  def withHeaders(headers: (String, String)*): RequestTransformer =
    appendHeaders( headers.map {
      case (h, _) if h.toLowerCase == "user-agent" => throw new UserAgentModificationNotSupportedException
      case (h, v) => RawHeader(h, v)
    } )

  def withUserAgent(value: String): RequestTransformer = appendHeaders(Seq(`User-Agent`(value)))

  def withCookie(cookie: (String, String)): RequestTransformer = withCookies(cookie)
  def withCookies(cookies: (String, String)*): RequestTransformer = appendHeaders( cookies.map(p => Cookie(p._1, p._2)) )


  private def appendHeaders[H <: HttpHeader](headers: Iterable[H]): RequestTransformer = r =>
    r.withHeaders( r.headers ++ headers)
}

trait HttpClientRequestBodyTransformers extends HttpClientContentTypes {
  def withPayload(body: String, contentType: ContentType = TextPlain): RequestTransformer = setBody(HttpEntity(contentType, ByteString(body)))
  def withPayload(bytes: Array[Byte], contentType: ContentType): RequestTransformer = setBody(HttpEntity(contentType, bytes))
  def withPayload(xml: Node): RequestTransformer = setBody(HttpEntity(XmlContent, WixHttpTestkitResources.xmlPrinter.format(xml)))

  def withPayload(entity: AnyRef)(implicit marshaller: Marshaller): RequestTransformer = setBody(HttpEntity(JsonContent, marshaller.marshall(entity)))

  def withFormData(formParams: (String, String)*): RequestTransformer = setBody(FormData(formParams.toMap).toEntity)

  def withMultipartData(parts: (String, RequestPart)*): RequestTransformer =
    setBody( Multipart.FormData(parts.map {
                                      case (n, p) => Multipart.FormData.BodyPart.Strict(n, p.asHttpEntity)
                                      }:_*)
                      .toEntity)


  private def setBody(entity: RequestEntity): RequestTransformer = _.copy(entity = entity)
}


trait HttpClientRequestTransformersOps  {
  implicit class TransformerConcatenation(first: RequestTransformer) {
    def and(second: RequestTransformer): RequestTransformer = first andThen second
  }
}

object RequestPartOps {

  implicit class `RequestPart --> HttpEntity`(private val r: RequestPart) extends AnyVal {
    def asHttpEntity: HttpEntity.Strict = r match {
      case PlainRequestPart(v, c) => HttpEntity(c, ByteString(v))
      case BinaryRequestPart(b, c) => HttpEntity(c, b)
      case FileRequestPart(f, c) => BinaryRequestPart(readfile(f), c).asHttpEntity
      case FileNameRequestPart(fn, c) => FileRequestPart(new File(fn), c).asHttpEntity
    }

    private def readfile(f: File) =
      handling(classOf[IoException])
        .by( { case e => throw new MultipartFileReadError(f.getName, e) }) {
          Files.readAllBytes(f.toPath)
        }
  }
}

