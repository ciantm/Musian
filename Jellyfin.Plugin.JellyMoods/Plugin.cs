using System;
using System.Collections.Generic;
using Jellyfin.Plugin.JellyMoods.Configuration;
using MediaBrowser.Common.Configuration;
using MediaBrowser.Common.Plugins;
using MediaBrowser.Model.Plugins;
using MediaBrowser.Model.Serialization;

namespace Jellyfin.Plugin.JellyMoods;

/// <summary>
/// JellyMoods — play music from your library based on a visual mood wheel.
/// </summary>
public class Plugin : BasePlugin<PluginConfiguration>, IHasWebPages
{
    /// <summary>
    /// Initializes a new instance of the <see cref="Plugin"/> class.
    /// </summary>
    public Plugin(IApplicationPaths applicationPaths, IXmlSerializer xmlSerializer)
        : base(applicationPaths, xmlSerializer)
    {
        Instance = this;
    }

    /// <summary>Gets the singleton instance.</summary>
    public static Plugin? Instance { get; private set; }

    /// <inheritdoc />
    /// <remarks>
    /// This GUID must never change — it is used by Jellyfin to identify the plugin
    /// in the plugin repository and in saved configuration.
    /// </remarks>
    public override Guid Id => Guid.Parse("6c8a80b7-3e2f-4d5a-9b1c-f7e8d9a0b2c3");

    /// <inheritdoc />
    public override string Name => "JellyMoods";

    /// <inheritdoc />
    public override string Description =>
        "Play music from your Jellyfin library based on mood selection using a visual emotion wheel.";

    /// <inheritdoc />
    public IEnumerable<PluginPageInfo> GetPages()
    {
        var ns = GetType().Namespace;
        return
        [
            // ── Main mood-picker page (visible to all users in the sidebar) ──
            new PluginPageInfo
            {
                // Page name must be lowercase, no spaces — used in the URL:
                // /web/index.html#!/configurationpage?name=jellymoods
                Name                 = "jellymoods",
                DisplayName          = "JellyMoods",
                EmbeddedResourcePath = $"{ns}.Configuration.moodPicker.html",
                EnableInMainMenu     = true,   // shows in sidebar for ALL users
                MenuIcon             = "music_note",
                MenuSection          = "user", // places it in the user section, not admin
            },

            // ── Admin settings page (Dashboard → Plugins → JellyMoods Settings) ──
            new PluginPageInfo
            {
                Name                 = "jellymoodsconfig",
                DisplayName          = "JellyMoods Settings",
                EmbeddedResourcePath = $"{ns}.Configuration.configPage.html",
                EnableInMainMenu     = false,
            },
        ];
    }
}
