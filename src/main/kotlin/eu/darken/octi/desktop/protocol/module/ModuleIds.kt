package eu.darken.octi.desktop.protocol.module

/**
 * Wire-stable module ID constants. Each value is the [ModuleId.id] string used in
 * `GET /v1/module/{moduleId}` and the @SerialName-equivalent identifier for the wire payload.
 *
 * Must match the `MODULE_ID` declarations in app-main's per-module `XxxModule.kt` files
 * (e.g. `modules-power/.../PowerModule.kt`). Any drift here means the desktop reads the wrong
 * module URL and silently shows no data.
 */
object ModuleIds {
    val POWER = ModuleId("eu.darken.octi.module.core.power")
    val META = ModuleId("eu.darken.octi.module.core.meta")
    val WIFI = ModuleId("eu.darken.octi.module.core.wifi")
    val CONNECTIVITY = ModuleId("eu.darken.octi.module.core.connectivity")
    val APPS = ModuleId("eu.darken.octi.module.core.apps")
    val CLIPBOARD = ModuleId("eu.darken.octi.module.core.clipboard")
    val FILES = ModuleId("eu.darken.octi.module.core.files")
}
