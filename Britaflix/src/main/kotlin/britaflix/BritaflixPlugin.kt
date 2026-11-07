package britaflix

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class BritaflixPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(BritaflixProvider())
    }
}
