using MediaBrowser.Model.Plugins;

namespace Jellyfin.Plugin.Musian.Configuration;

/// <summary>
/// Configuration for the Musian plugin.
/// Intentionally empty — Musian is entirely client-side. The mood vocabulary,
/// tag coordinates and genre zones are hardcoded tables in
/// Configuration/app.html, so there is nothing for an admin to configure.
/// Required only because BasePlugin&lt;T&gt; needs a config type.
/// </summary>
public class PluginConfiguration : BasePluginConfiguration
{
}
