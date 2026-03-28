using System.IO;
using System.Reflection;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.JellyMoods.Api;

/// <summary>
/// Serves the JellyMoods standalone app and sidebar injection script.
/// </summary>
[ApiController]
[Route("JellyMoods")]
public class PageController : ControllerBase
{
    private static string GetResource(string name)
    {
        var ns = typeof(Plugin).Namespace;
        var resourceName = $"{ns}.Configuration.{name}";
        using var stream = Assembly.GetExecutingAssembly()
            .GetManifestResourceStream(resourceName)
            ?? throw new FileNotFoundException($"Resource not found: {resourceName}");
        using var reader = new StreamReader(stream);
        return reader.ReadToEnd();
    }

    /// <summary>
    /// Serves the standalone JellyMoods app page at /JellyMoods/app.
    /// No Jellyfin dashboard shell — clicks work.
    /// </summary>
    [HttpGet("app")]
    [Produces("text/html")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public ContentResult GetApp()
        => Content(GetResource("app.html"), "text/html");

    /// <summary>
    /// Serves the sidebar injection script at /JellyMoods/sidebar.js.
    /// Loaded globally via config.json to add nav item for all users.
    /// </summary>
    [HttpGet("sidebar.js")]
    [Produces("application/javascript")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public ContentResult GetSidebarJs()
        => Content(GetResource("sidebar.js"), "application/javascript");
}
