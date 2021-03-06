package webby.mvc

import webby.api.{App, Application}

import scala.reflect.ClassTag

/**
  * Класс, ускоряющий доступ к плагину.
  * Важная фишка этого класса - можно спокойно перезапускать приложение. В таком случае плагин берётся из нового приложения.
  * Важно! Рекомендуется сохранять объект этого класса в *lazy* val.
  * Это должно обеспечить корректную инициализацию при одновременных запросах к объекту.
  * @tparam T Тип (класс) плагина
  */
class AppPluginHolder[T](implicit ct: ClassTag[T]) {
  private var app: Application = _
  private var plugin: T = _

  def get: T = {
    if (App.appOrNull == app) plugin
    else {
      synchronized {
        val curApp = App.appOrNull
        if (curApp == null) sys.error("Application not initialized")
        plugin = curApp.plugin[T].getOrElse(onDisabledPlugin)
        app = curApp
        plugin
      }
    }
  }

  def onDisabledPlugin: T = sys.error(s"Plugin $ct is not registered")
}

/**
  * Класс, ускоряющий доступ к плагину.
  * Важная фишка этого класса - можно спокойно перезапускать приложение. В таком случае плагин берётся из нового приложения.
  * Важно! Рекомендуется сохранять объект этого класса в *lazy* val.
  * Это должно обеспечить корректную инициализацию при одновременных запросах к объекту.
  *
  * Эта реализация отличается от [[AppPluginHolder]] тем, что она возвращает None, если плагин выключен.
  *
  * @tparam T Тип (класс) плагина
  */
class OptionalAppPluginHolder[T](implicit ct: ClassTag[T]) {
  private var app: Application = _
  private var maybePlugin: Option[T] = _

  def get: Option[T] = {
    if (App.appOrNull == app) maybePlugin
    else {
      synchronized {
        val curApp = App.appOrNull
        if (curApp == null) sys.error("Application not initialized")
        maybePlugin = curApp.plugin[T]
        app = curApp
        maybePlugin
      }
    }
  }
}
