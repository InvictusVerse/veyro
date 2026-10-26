using Veyro.Core.Handoff;

namespace Veyro.Data.Contracts;

/// <summary>
/// What the Explorer "Send with Veyro" menu shows and where an invoked entry goes (ADR-039).
/// Called on COM threads while Explorer builds its menu: every member must be fast (well under
/// 50 ms), thread-safe, and must not throw.
/// </summary>
public interface IExplorerMenuSource
{
    /// <summary>Setting "Show Send with Veyro in the Explorer right-click menu". False when unknown.</summary>
    bool IsEnabled();

    /// <summary>Paired phones with their connection state, for the submenu.</summary>
    IReadOnlyList<SendCandidate> GetPhones();

    /// <summary>Starts handling an invoked entry and returns at once.</summary>
    void Deliver(HandoffRequest request);
}
