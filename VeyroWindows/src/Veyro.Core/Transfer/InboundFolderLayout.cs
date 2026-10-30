using Veyro.Core.Link;
using Veyro.Core.Protocol;

namespace Veyro.Core.Transfer;

/// <summary>
/// Where a received offer lands (ADR-064). A folder keeps its structure under the destination;
/// when the name of its top folder is already taken there, the whole folder is saved as
/// "Photos (2)", "Photos (3)", ... so two copies never mix and nothing is overwritten.
///
/// Only the first segment of a path that is already valid is renamed. An invalid path is passed
/// on unchanged, so <see cref="BulkTransfer"/> rejects it exactly as before; a path is never
/// rewritten into one that passes (04-PROTOCOL.md section 8).
/// </summary>
public sealed class InboundFolderLayout
{
    /// <summary>"Photos (2)" up to "Photos (99)"; past that the folder goes into the first name.</summary>
    private const int MaxSuffix = 99;

    private readonly Dictionary<string, string> _tops;

    private InboundFolderLayout(Dictionary<string, string> tops, IReadOnlyList<InboundTransferItem> files, IReadOnlyList<string> directories, string? folderName)
    {
        _tops = tops;
        Files = files;
        EmptyDirectories = directories;
        FolderName = folderName;
    }

    /// <summary>The files to receive, under the paths they are saved at.</summary>
    public IReadOnlyList<InboundTransferItem> Files { get; }

    /// <summary>The folder's empty directories that passed validation, as saved.</summary>
    public IReadOnlyList<string> EmptyDirectories { get; }

    /// <summary>The saved name of the folder when the offer is one folder; otherwise null.</summary>
    public string? FolderName { get; }

    /// <param name="renameTakenFolders">False for an offer the PC asked for, which has its own folder.</param>
    public static InboundFolderLayout Plan(string destinationRoot, IReadOnlyList<LinkItem> items, bool renameTakenFolders = true)
    {
        var offered = new List<string>();
        var offeredKeys = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (LinkItem item in items)
        {
            if (RelativePath.Validate(item.RelPath) is not PathValidationResult.Valid valid) continue;
            if ((item.IsDir || valid.Segments.Count >= 2) && offeredKeys.Add(valid.Segments[0])) offered.Add(valid.Segments[0]);
        }
        var tops = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        var chosen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (string top in offered)
        {
            string name = top;
            for (int n = 1; renameTakenFolders && n <= MaxSuffix; n++)
            {
                string candidate = n == 1 ? top : $"{top} ({n})";
                if (!RelativePath.IsValid(candidate)) break;
                // Never the name another top folder of this same offer arrives under.
                if (chosen.Contains(candidate) || (!candidate.Equals(top, StringComparison.OrdinalIgnoreCase) && offeredKeys.Contains(candidate))) continue;
                if (Taken(destinationRoot, candidate)) continue;
                name = candidate;
                break;
            }
            chosen.Add(name);
            tops[top] = name;
        }

        var layout = new InboundFolderLayout(tops, [], [], null);
        var files = items.Where(item => !item.IsDir)
            .Select(item => new InboundTransferItem(item.Index, layout.SavedPath(item.RelPath), item.Size))
            .ToList();
        var directories = items.Where(item => item.IsDir && RelativePath.IsValid(item.RelPath))
            .Select(item => layout.SavedPath(item.RelPath))
            .ToList();
        string? folder = FolderNameOf(items) is { } sent && tops.TryGetValue(sent, out string? saved) ? saved : null;
        return new InboundFolderLayout(tops, files, directories, folder);
    }

    /// <summary><paramref name="relativePath"/> as it is saved; unchanged when it is not a valid path.</summary>
    public string SavedPath(string relativePath)
    {
        if (RelativePath.Validate(relativePath) is not PathValidationResult.Valid valid) return relativePath;
        if (!_tops.TryGetValue(valid.Segments[0], out string? saved)) return valid.Normalized;
        string renamed = string.Join("/", valid.Segments.Skip(1).Prepend(saved));
        // A rename that pushes a path past the limits keeps the original name instead.
        return RelativePath.IsValid(renamed) ? renamed : valid.Normalized;
    }

    /// <summary>
    /// Creates the folder's empty directories once its files are in, each validated and checked to
    /// stay inside <paramref name="destinationRoot"/>. Returns how many could not be made.
    /// </summary>
    public int CreateEmptyDirectories(string destinationRoot)
    {
        string root = Path.GetFullPath(destinationRoot).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        int failed = 0;
        foreach (string directory in EmptyDirectories)
        {
            try
            {
                if (!RelativePath.IsValid(directory)) throw new InvalidDataException("Invalid directory path.");
                string full = Path.GetFullPath(Path.Combine(root, directory.Replace('/', Path.DirectorySeparatorChar)));
                if (!full.StartsWith(root, StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException("Directory escaped the destination.");
                Directory.CreateDirectory(full);
            }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException or InvalidDataException)
            {
                failed++;
            }
        }
        return failed;
    }

    /// <summary>
    /// The top folder when every item sits under one first segment and one is nested or a
    /// directory; else null. The sender's name: shown, never trusted for anything else.
    /// </summary>
    public static string? FolderNameOf(IReadOnlyList<LinkItem> items)
    {
        if (items.Count == 0) return null;
        string top = items[0].RelPath.Split('/')[0];
        if (top.Length == 0) return null;
        bool allUnder = items.All(item => item.RelPath.StartsWith(top + "/", StringComparison.Ordinal) || (item.IsDir && item.RelPath == top));
        return allUnder && items.Any(item => item.IsDir || item.RelPath.Contains('/')) ? top : null;
    }

    private static bool Taken(string root, string name)
    {
        try
        {
            string full = Path.Combine(root, name);
            return Directory.Exists(full) || File.Exists(full);
        }
        catch (Exception error) when (error is IOException or ArgumentException or UnauthorizedAccessException)
        {
            return false;
        }
    }
}
