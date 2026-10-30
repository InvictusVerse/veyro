namespace Veyro.Core.Models;

public enum VerificationOutcome
{
    Pending,
    Verified,
    Failed,
    // Appended, so stored history (saved as numbers) keeps its meaning.
    Cancelled,
    Declined
}
