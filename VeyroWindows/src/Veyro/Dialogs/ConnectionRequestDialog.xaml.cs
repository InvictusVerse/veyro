namespace Veyro.Dialogs;

public sealed partial class ConnectionRequestDialog : ContentDialog
{
    public ConnectionRequestDialog(string peerName, string fingerprint, string sixDigitCode)
    {
        InitializeComponent();
        PinCodeText.Text = sixDigitCode;
        PeerInfoText.Text = $"{peerName} ({fingerprint})";
    }
}
