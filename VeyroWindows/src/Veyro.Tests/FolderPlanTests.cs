using Veyro.Core.Link;
using Veyro.Core.Protocol;
using Veyro.Core.Transfer;
using Xunit;

namespace Veyro.Tests;

/// <summary>ADR-064: a picked or dropped folder becomes the entries of one send.</summary>
public sealed class OutgoingPlannerTests
{
    /// <summary>A file system in memory: a path to its entry, a directory to its children.</summary>
    private sealed class FakeSource : IFolderSource
    {
        private readonly Dictionary<string, FolderSourceEntry> _entries = new(StringComparer.OrdinalIgnoreCase);
        private readonly Dictionary<string, List<FolderSourceEntry>> _children = new(StringComparer.OrdinalIgnoreCase);
        public HashSet<string> Unreadable { get; } = new(StringComparer.OrdinalIgnoreCase);

        public string Dir(string? parent, string name, FileAttributes attributes = FileAttributes.Directory, bool link = false)
        {
            string path = parent is null ? @"C:\" + name : parent + @"\" + name;
            var entry = new FolderSourceEntry(path, name, true, 0, DateTime.UnixEpoch, attributes, link);
            _entries[path] = entry;
            _children[path] = [];
            if (parent is not null) _children[parent].Add(entry);
            return path;
        }

        public string File(string? parent, string name, long size = 3, FileAttributes attributes = FileAttributes.Normal, bool link = false)
        {
            string path = parent is null ? @"C:\" + name : parent + @"\" + name;
            var entry = new FolderSourceEntry(path, name, false, size, DateTime.UnixEpoch, attributes, link);
            _entries[path] = entry;
            if (parent is not null) _children[parent].Add(entry);
            return path;
        }

        public FolderSourceEntry? Get(string path) => _entries.GetValueOrDefault(path);

        public IEnumerable<FolderSourceEntry> List(string directory)
            => Unreadable.Contains(directory) ? throw new UnauthorizedAccessException() : _children[directory];
    }

    private static List<string> Paths(OutgoingPlan plan) => plan.Entries.Select(e => e.RelativePath + (e.IsDirectory ? "/" : "")).ToList();

    [Fact]
    public void Folder_KeepsNestedPathsUnderItsName_FilesFirstThenEmptyDirectories()
    {
        var fs = new FakeSource();
        string trip = fs.Dir(null, "Trip");
        fs.File(trip, "a.jpg");
        string day = fs.Dir(trip, "Day 2");
        fs.File(day, "b.jpg", size: 10);
        fs.Dir(trip, "Empty");
        string deep = fs.Dir(day, "deep");
        fs.Dir(deep, "deeper");

        OutgoingPlan plan = OutgoingPlanner.Build([trip], fs);

        Assert.Equal(["Trip/a.jpg", "Trip/Day 2/b.jpg", "Trip/Day 2/deep/deeper/", "Trip/Empty/"], Paths(plan));
        Assert.Equal([0, 1, 2, 3], plan.Entries.Select(e => e.Index));
        Assert.Equal("Trip", plan.FolderName);
        Assert.Equal(2, plan.FileCount);
        Assert.Equal(13, plan.TotalBytes);
        Assert.True(plan.HasFolders);
        Assert.Equal("Trip folder, 2 files", plan.Describe());
        Assert.All(plan.Entries.Where(e => e.IsDirectory), e => Assert.Null(e.SourcePath));
    }

    [Fact]
    public void LinksHiddenSystemAndUnreadable_AreLeftOut()
    {
        var fs = new FakeSource();
        string root = fs.Dir(null, "Docs");
        fs.File(root, "keep.txt");
        fs.File(root, "desktop.ini", attributes: FileAttributes.Hidden | FileAttributes.System);
        fs.File(root, ".secret", attributes: FileAttributes.Hidden);
        fs.File(root, "shortcut-target.txt", link: true);
        fs.Dir(root, "Junction", FileAttributes.Directory | FileAttributes.ReparsePoint, link: true);
        string locked = fs.Dir(root, "Locked");
        fs.Unreadable.Add(locked);
        // A OneDrive placeholder is a reparse point but no link: it is sent.
        fs.File(root, "cloud.docx", attributes: FileAttributes.ReparsePoint);

        OutgoingPlan plan = OutgoingPlanner.Build([root], fs);

        Assert.Equal(["Docs/cloud.docx", "Docs/keep.txt"], Paths(plan));
        Assert.Equal(3, plan.Skipped);
        Assert.Equal(2, plan.Hidden);
    }

    [Fact]
    public void Names_AreSanitisedAndMadeDistinctIgnoringCase()
    {
        var fs = new FakeSource();
        string root = fs.Dir(null, "Mixed");
        fs.File(root, "Photo.JPG");
        fs.File(root, "photo.jpg");
        fs.File(root, "CON.txt");
        fs.File(root, "trailing. ");

        List<string> paths = Paths(OutgoingPlanner.Build([root], fs));

        Assert.Contains("Mixed/Photo.JPG", paths);
        Assert.Contains("Mixed/photo (2).jpg", paths);
        Assert.Contains("Mixed/_CON.txt", paths);
        Assert.Contains("Mixed/trailing", paths);
        Assert.All(paths, path => Assert.True(RelativePath.IsValid(path.TrimEnd('/'))));
    }

    [Fact]
    public void PathsTheReceiverWouldReject_AreLeftOutAndCounted()
    {
        var fs = new FakeSource();
        string root = fs.Dir(null, "Top");
        string parent = root;
        for (int i = 0; i < 70; i++) parent = fs.Dir(parent, "d");
        fs.File(parent, "deep.txt");
        fs.File(root, new string('n', 1100) + ".txt");
        fs.File(root, "fine.txt");

        OutgoingPlan plan = OutgoingPlanner.Build([root], fs);

        Assert.Equal(["Top/fine.txt"], plan.Files.Select(e => e.RelativePath));
        Assert.True(plan.Skipped >= 2);
        Assert.All(Paths(plan), path => Assert.True(RelativePath.IsValid(path.TrimEnd('/'))));
    }

    [Fact]
    public void TooManyEntries_IsRefusedWhole()
    {
        var fs = new FakeSource();
        string root = fs.Dir(null, "Huge");
        for (int i = 0; i <= OutgoingPlanner.MaxEntries; i++) fs.File(root, $"f{i}.txt");

        Assert.Throws<FolderTooLargeException>(() => OutgoingPlanner.Build([root], fs));
    }

    [Fact]
    public void FilesAndFoldersTogether_AreNotOneFolder_AndTopNamesStayDistinct()
    {
        var fs = new FakeSource();
        string one = fs.Dir(null, "Photos");
        fs.File(one, "a.jpg");
        string other = fs.Dir(fs.Dir(null, "Elsewhere"), "photos");
        fs.File(other, "b.jpg");
        string loose = fs.File(null, "notes.txt");
        string gone = @"C:\missing.txt";

        OutgoingPlan plan = OutgoingPlanner.Build([one, other, loose, gone], fs);

        Assert.Null(plan.FolderName);
        Assert.Equal(["Photos/a.jpg", "photos (2)/b.jpg", "notes.txt"], plan.Files.Select(e => e.RelativePath));
        Assert.Equal(1, plan.Skipped);
        Assert.Equal("3 files", plan.Describe());
    }

    [Fact]
    public void LooseFiles_AreSentUnderTheirNames()
    {
        var fs = new FakeSource();
        string a = fs.File(null, "a.txt");
        OutgoingPlan plan = OutgoingPlanner.Build([a], fs);
        Assert.False(plan.HasFolders);
        Assert.Equal("a.txt", plan.Describe());
        Assert.Equal(a, Assert.Single(plan.Entries).SourcePath);
    }

    [Fact]
    public void RealDisk_WalksAFolderAndSkipsHiddenFiles()
    {
        string root = Path.Combine(Path.GetTempPath(), "veyro-plan-" + Guid.NewGuid().ToString("N"));
        string trip = Path.Combine(root, "Trip");
        try
        {
            Directory.CreateDirectory(Path.Combine(trip, "Day 2"));
            Directory.CreateDirectory(Path.Combine(trip, "Empty"));
            File.WriteAllText(Path.Combine(trip, "a.txt"), "A");
            File.WriteAllText(Path.Combine(trip, "Day 2", "b.txt"), "BB");
            string hidden = Path.Combine(trip, "thumbs.db");
            File.WriteAllText(hidden, "x");
            File.SetAttributes(hidden, FileAttributes.Hidden);

            OutgoingPlan plan = OutgoingPlanner.Build([trip]);

            Assert.Equal(["Trip/a.txt", "Trip/Day 2/b.txt", "Trip/Empty/"], Paths(plan));
            Assert.Equal(3, plan.TotalBytes);
            Assert.Equal(1, plan.Hidden);
            Assert.Equal(Path.Combine(trip, "Day 2", "b.txt"), plan.Files[1].SourcePath);
        }
        finally
        {
            try { Directory.Delete(root, recursive: true); } catch { }
        }
    }
}

/// <summary>ADR-064: a received folder keeps its structure and never mixes with a copy already there.</summary>
public sealed class InboundFolderLayoutTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "veyro-layout-" + Guid.NewGuid().ToString("N"));

    public InboundFolderLayoutTests() => Directory.CreateDirectory(_root);

    public void Dispose()
    {
        try { Directory.Delete(_root, recursive: true); } catch { }
    }

    private static LinkItem File(int index, string path, long size = 3) => new(index, path, size, 0);
    private static LinkItem Dir(int index, string path) => new(index, path, 0, 0, IsDir: true);

    [Fact]
    public void FreeFolder_KeepsItsName()
    {
        var layout = InboundFolderLayout.Plan(_root, [File(0, "Trip/a.jpg"), File(1, "Trip/Day 2/b.jpg"), Dir(2, "Trip/Empty")]);

        Assert.Equal("Trip", layout.FolderName);
        Assert.Equal(["Trip/a.jpg", "Trip/Day 2/b.jpg"], layout.Files.Select(f => f.RelativePath));
        Assert.Equal(["Trip/Empty"], layout.EmptyDirectories);
    }

    [Fact]
    public void TakenFolder_GetsTheNextFreeNumberForEveryItem()
    {
        Directory.CreateDirectory(Path.Combine(_root, "Trip"));
        System.IO.File.WriteAllText(Path.Combine(_root, "Trip (2)"), "a file of that name");

        var layout = InboundFolderLayout.Plan(_root, [File(0, "Trip/a.jpg"), Dir(1, "Trip/Empty")]);

        Assert.Equal("Trip (3)", layout.FolderName);
        Assert.Equal("Trip (3)/a.jpg", Assert.Single(layout.Files).RelativePath);
        Assert.Equal(0, layout.CreateEmptyDirectories(_root));
        Assert.True(Directory.Exists(Path.Combine(_root, "Trip (3)", "Empty")));
    }

    [Fact]
    public void LooseFiles_AndRequestedOffers_AreNotRenamed()
    {
        Directory.CreateDirectory(Path.Combine(_root, "Trip"));
        Assert.Equal("Trip/a.jpg", Assert.Single(InboundFolderLayout.Plan(_root, [File(0, "Trip/a.jpg")], renameTakenFolders: false).Files).RelativePath);
        var loose = InboundFolderLayout.Plan(_root, [File(0, "a.jpg"), File(1, "b.jpg")]);
        Assert.Null(loose.FolderName);
        Assert.Equal(["a.jpg", "b.jpg"], loose.Files.Select(f => f.RelativePath));
    }

    [Theory]
    [InlineData("Trip/../escape.txt")]
    [InlineData("/etc/passwd")]
    [InlineData("C:/Windows/x.dll")]
    [InlineData("//server/share/x")]
    [InlineData("Trip\\x")]
    [InlineData("Trip/CON/a.txt")]
    [InlineData("Trip/a.txt:stream")]
    public void InvalidPaths_PassThroughUnchanged_SoBulkTransferRejectsThem(string hostile)
    {
        Directory.CreateDirectory(Path.Combine(_root, "Trip"));
        var layout = InboundFolderLayout.Plan(_root, [File(0, "Trip/ok.txt"), File(1, hostile), Dir(2, hostile)]);

        Assert.Equal(hostile, layout.Files.Single(f => f.Index == 1).RelativePath);
        Assert.False(RelativePath.IsValid(layout.Files.Single(f => f.Index == 1).RelativePath));
        Assert.Empty(layout.EmptyDirectories);
    }

    [Fact]
    public void TooLongAndTooDeep_AreNotDirectoriesToMake()
    {
        string deep = string.Join("/", Enumerable.Repeat("d", 70).Prepend("Trip"));
        string longName = "Trip/" + new string('n', 1100);
        var layout = InboundFolderLayout.Plan(_root, [Dir(0, deep), Dir(1, longName), Dir(2, "Trip/ok")]);

        Assert.Equal(["Trip/ok"], layout.EmptyDirectories);
        Assert.Equal(0, layout.CreateEmptyDirectories(_root));
        Assert.False(Directory.Exists(Path.Combine(_root, "d")));
    }

    [Fact]
    public void TwoFoldersOfOneOffer_NeverShareASavedName()
    {
        Directory.CreateDirectory(Path.Combine(_root, "A"));
        var layout = InboundFolderLayout.Plan(_root, [File(0, "A/x"), File(1, "A (2)/y")]);

        Assert.Equal(["A (3)/x", "A (2)/y"], layout.Files.Select(f => f.RelativePath));
    }
}
