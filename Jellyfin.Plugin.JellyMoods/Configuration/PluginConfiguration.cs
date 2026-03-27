using MediaBrowser.Model.Plugins;

namespace Jellyfin.Plugin.JellyMoods.Configuration;

/// <summary>
/// Configuration for the JellyMoods plugin.
/// Each quadrant maps to a comma-separated list of genres used to filter tracks.
/// </summary>
public class PluginConfiguration : BasePluginConfiguration
{
    // ── Quadrant genre mappings ──────────────────────────────────────────────

    /// <summary>Top-right: High arousal, positive valence.</summary>
    public string HappyExcitedGenres { get; set; } =
        "Pop,Dance,Electronic,Funk,Party,House,Disco";

    /// <summary>Top-left: High arousal, negative valence.</summary>
    public string AngryTenseGenres { get; set; } =
        "Metal,Punk,Hard Rock,Industrial,Hardcore,Thrash Metal";

    /// <summary>Bottom-right: Low arousal, positive valence.</summary>
    public string CalmRelaxedGenres { get; set; } =
        "Jazz,Ambient,Classical,Acoustic,Chill,New Age,Bossa Nova";

    /// <summary>Bottom-left: Low arousal, negative valence.</summary>
    public string SadMelancholicGenres { get; set; } =
        "Blues,Soul,Folk,Indie,Singer-Songwriter,Slowcore";

    // ── Playback options ─────────────────────────────────────────────────────

    /// <summary>Number of tracks to add to the queue.</summary>
    public int PlaylistSize { get; set; } = 30;

    /// <summary>Whether to shuffle the resulting playlist.</summary>
    public bool ShufflePlaylist { get; set; } = true;

    /// <summary>
    /// Blend radius — how much to blend adjacent quadrant genres (0.0–1.0).
    /// Higher values include more genres from neighbouring moods.
    /// </summary>
    public float BlendRadius { get; set; } = 0.35f;
}
