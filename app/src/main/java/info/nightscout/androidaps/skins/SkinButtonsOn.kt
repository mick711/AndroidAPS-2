package info.nightscout.androidaps.skins

import info.nightscout.androidaps.interfaces.Config
import info.nightscout.androidaps.R
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SkinButtonsOn @Inject constructor(private val config: Config) : SkinInterface {

    override val description: Int get() = R.string.buttonson_description
    override val mainGraphHeight: Int get() = 200
    override val secondaryGraphHeight: Int get() = 100
}