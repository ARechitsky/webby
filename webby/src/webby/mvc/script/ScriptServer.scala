package webby.mvc.script

import java.io.ByteArrayOutputStream
import java.nio.file.{Files, Path}
import java.util.zip.GZIPOutputStream

import com.google.common.net.HttpHeaders._
import compiler.ScriptCompiler
import io.netty.handler.codec.http.HttpResponseStatus
import watcher.{FileExtTransform, TargetFileTransform, Watcher}
import webby.api.App
import webby.api.http.ContentTypes
import webby.api.libs.MimeTypes
import webby.api.mvc._
import webby.commons.time.StdDates
import webby.commons.system.log.PageLog
import webby.mvc.{StdCtl, StdPaths}

import scala.annotation.tailrec

class ScriptServer(val watchDir: Path,
                   val targetDir: Path,
                   val compilers: List[ScriptCompiler],
                   val watcherFactory: Path => Watcher)
  extends StdCtl {

  val log = webby.api.Logger(getClass)

  Files.createDirectories(targetDir)
  val watchPath: String = watchDir.toAbsolutePath.toString
  val watcher: Watcher = watcherFactory(watchDir)

  def at(path: String): Action = SimpleAction {implicit req =>
    PageLog.noLog()
    at(path, compilers)
  }

  @tailrec
  private def at(path: String, compilersLeft: List[ScriptCompiler])(implicit req: RequestHeader): PlainResult =
    compilersLeft match {
      case Nil => serveFile(watchDir.resolve(path), compilers.head.targetContentType)
      case compiler :: tail =>
        path match {
          case _ if path.endsWith(compiler.targetDotExt) =>
            watchDir.resolve(new FileExtTransform(compiler.sourceFileExt).transform(path)) match {
              case p if Files.exists(p) =>
                val filePath: String = p.toAbsolutePath.toString
                if (!filePath.startsWith(watchPath)) {
                  BadRequest
                } else {
                  val targetPath: Path = TargetFileTransform(watchDir, targetDir, compiler.targetFileExt).transformToPath(filePath)
                  def checkRecompile(): Boolean = {
                    synchronized {
                      if (!Files.exists(targetPath) || watcher.pollFile(p, targetPath)) {
                        val t0 = System.currentTimeMillis()
                        val compileResult = compiler.compileFile(p, targetPath)
                        val t1 = System.currentTimeMillis()
                        if (compileResult.isLeft) {
                          log.error("Error compiling " + path + ":\n" + compileResult.left.get)
                          return false
                        }
                        log.info("Compiled " + path + " in " + (t1 - t0) + " ms")
                      }
                    }
                    true
                  }

                  if (checkRecompile()) {
                    serveFile(targetPath, compiler.targetContentType)
                  } else {
                    InternalServerError
                  }
                }
              case _ => at(path, tail)
            }
          case _ if compiler.sourceMapDotExt.exists(path.endsWith) => serveFile(targetDir.resolve(path), compiler.targetContentType)
          case _ => at(path, tail)
        }
    }

  def serveFile(path: Path, contentType: String)(implicit req: RequestHeader): PlainResult = {
    if (!Files.exists(path)) NotFoundRaw
    else {
      // Если не найден файл для конвертации, но есть уже готовый файл, то мы просто отдаём готовый файл.
      val lastModified = Files.getLastModifiedTime(path).toMillis
      if (req.headers.get(IF_MODIFIED_SINCE).contains(lastModified.toString)) {
        PlainResult(HttpResponseStatus.NOT_MODIFIED)
      } else {
        ScriptServer.maybeGzippedResult(Files.readAllBytes(path))
          .withHeader(CONTENT_TYPE, contentType)
          .withHeader(LAST_MODIFIED, StdDates.httpDateFormatMillis(lastModified))
      }
    }
  }
}

object ScriptServer {

  def apply(paths: StdPaths.Value, relativePath: String, compilers: List[ScriptCompiler], watcherFactory: Path => Watcher): String => Action = {
    if (!App.isDev) sys.error("ScriptServer can work only with Profile.Dev")
    val server = new ScriptServer(
      watchDir = paths.assets.resolve(relativePath),
      targetDir = paths.targetAssets.resolve(relativePath),
      compilers, watcherFactory)
    (path) => server.at(path)
  }


  def serveFile(path: Path): PlainResult = {
    path match {
      case p if Files.exists(p) =>
        // Если не найден файл для конвертации, но есть уже готовый файл, то мы просто отдаём готовый файл.
        PlainResult(HttpResponseStatus.OK, Files.readAllBytes(p))
          .withHeader(CONTENT_TYPE, MimeTypes.forFileName(path.getFileName.toString).getOrElse(ContentTypes.BINARY))
      case p => Results.NotFoundRaw(p.toAbsolutePath.toString)
    }
  }

  def simpleServer(basePath: Path): (String) => Action =
    (path: String) => SimpleAction {_ => PageLog.noLog(); serveFile(basePath.resolve(path))}


  private[script] def maybeGzippedResult(resultBytes: Array[Byte])(implicit req: RequestHeader): PlainResult = {
    if (req.headers.get(ACCEPT_ENCODING).exists(_.contains("gzip"))) {
      PlainResult(HttpResponseStatus.OK, {
        val bos = new ByteArrayOutputStream()
        val stream = new GZIPOutputStream(bos)
        stream.write(resultBytes)
        stream.close()
        bos.close()
        bos.toByteArray
      }).withHeader(CONTENT_ENCODING, "gzip")
    } else {
      PlainResult(HttpResponseStatus.OK, resultBytes)
    }
  }
}