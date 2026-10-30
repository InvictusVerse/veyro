using System.Security;
using Veyro.Core.Protocol;

namespace Veyro.Core.Transfer;

/// <summary>
/// One entry of an outgoing transfer: a file to stream, or an empty directory of a folder
/// (ADR-064), which is offered with <c>isDir</c> and never streamed. Files come first, so an
/// entry's index is also its place among the files.
/// </summary>
public sealed record OutgoingEntry(int Index, string? SourcePath, string RelativePath, long Size, long ModifiedTicks, bool IsDirectory);

/// <summary>
/// What one send offers, built from the paths the user picked or dropped. Paths and names are
/// user data and are never logged.
/// </summary>
/// <param name="FolderName">The folder's name when exactly one folder (and no loose file) was given.</param>
/// <param name="Skipped">Entries left out that the user may miss: unreadable, links, paths too long or too deep.</param>
/// <param name="Hidden">Hidden and system entries inside a folder, left out as Explorer hides them.</param>
public sealed record OutgoingPlan(IReadOnlyList<OutgoingEntry> Entries, string? FolderName, int Skipped, int Hidden = 0)
{
    public IReadOnlyList<OutgoingEntry> Files => Entries.Where(entry => !entry.IsDirectory).ToList();
    public int FileCount => Entries.Count(entry => !entry.IsDirectory);
    public long TotalBytes => Entries.Where(entry => !entry.IsDirectory).Sum(entry => entry.Size);

    /// <summary>Needs a phone that takes folders (FOLDER_TRANSFER).</summary>
    public bool HasFolders => Entries.Any(entry => entry.IsDirectory || entry.RelativePath.Contains('/'));

    /// <summary>"Photos folder, 120 files", the one file's name, or "3 files": banner, toast and history.</summary>
    public string Describe() => FolderName is { } name
        ? FolderSummary(name, FileCount)
        : FileCount == 1 ? Files[0].RelativePath : $"{FileCount} files";

    public static string FolderSummary(string name, int fileCount)
        => $"{name} folder, {(fileCount == 1 ? "1 file" : $"{fileCount} files")}";
}

/// <summary>A folder too big for one send: more than <see cref="OutgoingPlanner.MaxEntries"/> entries.</summary>
public sealed class FolderTooLargeException(string message) : IOException(message);

/// <summary>The file system as the planner reads it, so tests can stand in for it.</summary>
public interface IFolderSource
{
    /// <summary>The entry at <paramref name="path"/>, or null when nothing is there.</summary>
    FolderSourceEntry? Get(string path);

    /// <summary>The entries of <paramref name="directory"/>. Throws when it cannot be listed.</summary>
    IEnumerable<FolderSourceEntry> List(string directory);
}

/// <param name="IsLink">A symbolic link, junction or mount point (a name-surrogate reparse point).</param>
public sealed record FolderSourceEntry(
    string Path, string Name, bool IsDirectory, long Size, DateTime LastWriteUtc, FileAttributes Attributes, bool IsLink = false);

/// <summary>
/// Builds the plan of a send from picked paths (ADR-064). A file is sent under its own name; a
/// folder is walked:
/// <list type="bullet">
/// <item>its name is the top segment and every entry keeps its path below it;</item>
/// <item>every segment is sanitised, and names equal ignoring case get " (2)", because the phone's
/// shared storage and this PC both treat them as one file;</item>
/// <item>symbolic links, junctions and mount points are not followed: they can loop or reach out of
/// the folder. Cloud placeholders (OneDrive) are reparse points too but are ordinary files to the
/// user, so they are sent (reading one downloads it, as Explorer's copy would);</item>
/// <item>hidden and system entries inside a folder are left out, as Explorer shows them by default
/// (desktop.ini, Thumbs.db). A hidden item picked by itself is sent;</item>
/// <item>a path the receiver would reject (64 segments, 1024 bytes) is left out and counted;</item>
/// <item>an empty directory becomes a directory entry;</item>
/// <item>more than <see cref="MaxEntries"/> entries is refused whole.</item>
/// </list>
/// </summary>
public static class OutgoingPlanner
{
    /// <summary>Files plus empty directories in one send; the offer stays well under 24 MB.</summary>
    public const int MaxEntries = 10_000;

    public static OutgoingPlan Build(IReadOnlyList<string> paths, IFolderSource? source = null)
    {
        var walk = new Walk(source ?? DiskFolderSource.Instance);
        return walk.Run(paths);
    }

    private sealed class Walk(IFolderSource source)
    {
        private readonly List<(string Path, string Relative, long Size, long Ticks)> _files = [];
        private readonly List<(string Relative, long Ticks)> _directories = [];
        private int _skipped;
        private int _hidden;

        public OutgoingPlan Run(IReadOnlyList<string> paths)
        {
            var topNames = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            int folders = 0, looseFiles = 0;
            string? folderName = null;
            foreach (string path in paths)
            {
                FolderSourceEntry? entry;
                try { entry = source.Get(path); }
                catch (Exception error) when (error is IOException or UnauthorizedAccessException or SecurityException or ArgumentException)
                {
                    entry = null;
                }
                if (entry is null)
                {
                    _skipped++;
                    continue;
                }
                string name = Unique(RelativePath.SanitizeOutgoingSegment(entry.Name), topNames);
                if (entry.IsDirectory)
                {
                    folders++;
                    folderName = name;
                    if (!Visit(entry.Path, name)) _directories.Add((name, entry.LastWriteUtc.Ticks));
                }
                else if (RelativePath.IsValid(name))
                {
                    looseFiles++;
                    Add(entry, name);
                }
                else
                {
                    _skipped++;
                }
            }
            var entries = _files
                .Select((file, index) => new OutgoingEntry(index, file.Path, file.Relative, file.Size, file.Ticks, false))
                .Concat(_directories.Select((dir, index) => new OutgoingEntry(_files.Count + index, null, dir.Relative, 0, dir.Ticks, true)))
                .ToList();
            return new OutgoingPlan(entries, folders == 1 && looseFiles == 0 ? folderName : null, _skipped, _hidden);
        }

        /// <summary>Walks <paramref name="directory"/>; true when it holds anything sent or left out.</summary>
        private bool Visit(string directory, string relative)
        {
            List<FolderSourceEntry> children;
            try { children = source.List(directory).ToList(); }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException or SecurityException)
            {
                _skipped++;
                return true;
            }
            var names = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            bool holds = false;
            foreach (FolderSourceEntry child in children.OrderBy(child => child.Name, StringComparer.OrdinalIgnoreCase))
            {
                holds = true;
                if (child.IsLink)
                {
                    _skipped++;
                    continue;
                }
                if ((child.Attributes & (FileAttributes.Hidden | FileAttributes.System)) != 0)
                {
                    _hidden++;
                    continue;
                }
                string name = Unique(RelativePath.SanitizeOutgoingSegment(child.Name), names);
                string path = $"{relative}/{name}";
                if (!RelativePath.IsValid(path))
                {
                    _skipped++;
                    continue;
                }
                if (child.IsDirectory)
                {
                    if (!Visit(child.Path, path)) AddDirectory(path, child.LastWriteUtc.Ticks);
                }
                else
                {
                    Add(child, path);
                }
            }
            return holds;
        }

        private void Add(FolderSourceEntry file, string relative)
        {
            Count();
            _files.Add((file.Path, relative, file.Size, file.LastWriteUtc.Ticks));
        }

        private void AddDirectory(string relative, long ticks)
        {
            Count();
            _directories.Add((relative, ticks));
        }

        private void Count()
        {
            if (_files.Count + _directories.Count >= MaxEntries)
                throw new FolderTooLargeException($"This has more than {MaxEntries:N0} items. Send a smaller folder.");
        }

        /// <summary><paramref name="name"/>, or "name (2).ext" and so on when it is taken, ignoring case.</summary>
        private static string Unique(string name, HashSet<string> taken)
        {
            if (taken.Add(name)) return name;
            int dot = name.LastIndexOf('.');
            string stem = dot > 0 ? name[..dot] : name;
            string extension = dot > 0 ? name[dot..] : string.Empty;
            for (int n = 2; ; n++)
            {
                string candidate = $"{stem} ({n}){extension}";
                if (taken.Add(candidate)) return candidate;
            }
        }
    }
}

/// <summary>The real file system. Links are reported, never followed.</summary>
internal sealed class DiskFolderSource : IFolderSource
{
    public static readonly DiskFolderSource Instance = new();

    public FolderSourceEntry? Get(string path)
    {
        if (string.IsNullOrWhiteSpace(path)) return null;
        string full = Path.GetFullPath(path);
        FileSystemInfo info = Directory.Exists(full) ? new DirectoryInfo(full) : new FileInfo(full);
        return info.Exists ? ToEntry(info) : null;
    }

    public IEnumerable<FolderSourceEntry> List(string directory)
    {
        var options = new EnumerationOptions
        {
            // Hidden and system entries are reported and skipped by the planner, so it can count them.
            AttributesToSkip = 0,
            IgnoreInaccessible = false,
            RecurseSubdirectories = false,
            ReturnSpecialDirectories = false,
        };
        return new DirectoryInfo(directory).EnumerateFileSystemInfos("*", options).Select(ToEntry).ToList();
    }

    private static FolderSourceEntry ToEntry(FileSystemInfo info)
    {
        bool directory = info is DirectoryInfo;
        bool link = (info.Attributes & FileAttributes.ReparsePoint) != 0 && info.LinkTarget is not null;
        return new FolderSourceEntry(
            info.FullName,
            // A drive root ("D:\") has no name of its own.
            info.Name.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar, ':') is { Length: > 0 } name ? name : "Drive",
            directory,
            directory ? 0 : ((FileInfo)info).Length,
            info.LastWriteTimeUtc,
            info.Attributes,
            link);
    }
}
