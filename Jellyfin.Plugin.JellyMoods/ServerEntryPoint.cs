using System;
using System.IO;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.JellyMoods;

/// <summary>
/// Runs on Jellyfin startup — patches web/config.json to globally load
/// sidebar.js so JellyMoods appears in the main nav for all users.
/// Uses IHostedService which is the correct interface for Jellyfin 10.10+.
/// </summary>
public class ServerEntryPoint : IHostedService
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
    public Task StartAsync(CancellationToken cancellationToken)
    {
        try
        {
            PatchWebConfig();
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "[JellyMoods] Could not patch web config");
        }

        return Task.CompletedTask;
    }

    /// <inheritdoc />
    public Task StopAsync(CancellationToken cancellationToken)
        => Task.CompletedTask;

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
            if (File.Exists(c)) { configPath = c; break; }
        }

        if (configPath is null)
        {
            _logger.LogWarning("[JellyMoods] web config.json not found — tried: {Paths}",
                string.Join(", ", candidates));
            return;
        }

        _logger.LogInformation("[JellyMoods] Patching {Path}", configPath);

        var json = JsonNode.Parse(File.ReadAllText(configPath)) as JsonObject
                   ?? new JsonObject();

        if (json["plugins"] is not JsonArray plugins)
        {
            plugins = new JsonArray();
            json["plugins"] = plugins;
        }

        foreach (var node in plugins)
        {
            if (node is JsonObject obj && obj["src"]?.GetValue<string>() == ScriptSrc)
            {
                _logger.LogInformation("[JellyMoods] sidebar.js already registered");
                return;
            }
        }

        plugins.Add(new JsonObject { ["src"] = ScriptSrc, ["type"] = ScriptType });

        File.WriteAllText(configPath,
            json.ToJsonString(new JsonSerializerOptions { WriteIndented = true }));

        _logger.LogInformation("[JellyMoods] sidebar.js registered in config.json");
    }
}
