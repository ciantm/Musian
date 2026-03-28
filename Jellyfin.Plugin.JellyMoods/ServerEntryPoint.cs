using System;
using System.IO;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using MediaBrowser.Controller.Plugins;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.JellyMoods;

/// <summary>
/// Runs on Jellyfin startup — patches web/config.json to load sidebar.js
/// globally so the JellyMoods nav item appears in the main sidebar
/// for all users on every page, without requiring a prior visit.
/// </summary>
public class ServerEntryPoint : IServerEntryPoint
{
    private const string ScriptSrc  = "/JellyMoods/sidebar.js";
    private const string ScriptType = "module";

    private readonly ILogger<ServerEntryPoint> _logger;

    /// <summary>
    /// Initializes a new instance of the <see cref="ServerEntryPoint"/> class.
    /// </summary>
    public ServerEntryPoint(ILogger<ServerEntryPoint> logger)
    {
        _logger = logger;
    }

    /// <inheritdoc />
    public Task RunAsync(CancellationToken cancellationToken)
    {
        try
        {
            PatchWebConfig();
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "[JellyMoods] Could not patch web config — sidebar item may not appear in main nav");
        }

        return Task.CompletedTask;
    }

    private void PatchWebConfig()
    {
        var baseDir = AppDomain.CurrentDomain.BaseDirectory;

        var candidates = new[]
        {
            Path.Combine(baseDir, "jellyfin-web", "config.json"),
            Path.Combine(baseDir, "web", "config.json"),
            "/usr/share/jellyfin/web/config.json",
            "/usr/lib/jellyfin/bin/jellyfin-web/config.json",
            "/usr/share/jellyfin-web/config.json",
        };

        string? configPath = null;
        foreach (var c in candidates)
        {
            if (File.Exists(c))
            {
                configPath = c;
                break;
            }
        }

        if (configPath is null)
        {
            _logger.LogWarning("[JellyMoods] config.json not found — tried: {Paths}", string.Join(", ", candidates));
            return;
        }

        _logger.LogInformation("[JellyMoods] Patching {ConfigPath}", configPath);

        var raw  = File.ReadAllText(configPath);
        var json = JsonNode.Parse(raw) as JsonObject ?? new JsonObject();

        if (json["plugins"] is not JsonArray plugins)
        {
            plugins = new JsonArray();
            json["plugins"] = plugins;
        }

        foreach (var node in plugins)
        {
            if (node is JsonObject obj && obj["src"]?.GetValue<string>() == ScriptSrc)
            {
                _logger.LogInformation("[JellyMoods] sidebar.js already registered in config.json");
                return;
            }
        }

        plugins.Add(new JsonObject
        {
            ["src"]  = ScriptSrc,
            ["type"] = ScriptType,
        });

        var opts = new JsonSerializerOptions { WriteIndented = true };
        File.WriteAllText(configPath, json.ToJsonString(opts));
        _logger.LogInformation("[JellyMoods] sidebar.js registered successfully");
    }

    /// <inheritdoc />
    public void Dispose() { }
}
