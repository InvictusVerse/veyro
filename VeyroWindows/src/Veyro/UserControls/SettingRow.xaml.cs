using Microsoft.UI.Xaml.Markup;

namespace Veyro.UserControls;

/// <summary>
/// A labelled row for the settings page. Whatever is placed inside the element (the
/// <see cref="EndContent"/>) sits at the end of the row; <see cref="BodyContent"/> goes under the text.
/// </summary>
[ContentProperty(Name = nameof(EndContent))]
public sealed partial class SettingRow : UserControl
{
    public static readonly DependencyProperty GlyphProperty = Register(nameof(Glyph), typeof(string), (row, v) =>
    {
        string glyph = v as string ?? string.Empty;
        row.GlyphIcon.Glyph = glyph;
        row.GlyphIcon.Visibility = glyph.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
    });

    public static readonly DependencyProperty TitleProperty = Register(nameof(Title), typeof(string), (row, v) => ShowText(row.TitleText, v as string));

    public static readonly DependencyProperty HintProperty = Register(nameof(Hint), typeof(string), (row, v) => ShowText(row.HintText, v as string));

    public static readonly DependencyProperty EndContentProperty = Register(nameof(EndContent), typeof(object), (row, v) => row.EndSlot.Content = v);

    public static readonly DependencyProperty BodyContentProperty = Register(nameof(BodyContent), typeof(object), (row, v) => row.BodySlot.Content = v);

    public SettingRow()
    {
        InitializeComponent();
    }

    /// <summary>Segoe Fluent Icons glyph shown before the text. Empty for none.</summary>
    public string Glyph
    {
        get => (string)GetValue(GlyphProperty);
        set => SetValue(GlyphProperty, value);
    }

    public string Title
    {
        get => (string)GetValue(TitleProperty);
        set => SetValue(TitleProperty, value);
    }

    /// <summary>Smaller explanatory text under the title.</summary>
    public string Hint
    {
        get => (string)GetValue(HintProperty);
        set => SetValue(HintProperty, value);
    }

    /// <summary>The control at the end of the row (switch, button, combo box).</summary>
    public object? EndContent
    {
        get => GetValue(EndContentProperty);
        set => SetValue(EndContentProperty, value);
    }

    /// <summary>Extra content placed under the title and hint.</summary>
    public object? BodyContent
    {
        get => GetValue(BodyContentProperty);
        set => SetValue(BodyContentProperty, value);
    }

    private static void ShowText(TextBlock block, string? text)
    {
        block.Text = text ?? string.Empty;
        block.Visibility = string.IsNullOrEmpty(text) ? Visibility.Collapsed : Visibility.Visible;
    }

    /// <summary>Registers a property whose change is pushed straight into the row's parts.</summary>
    private static DependencyProperty Register(string name, Type type, Action<SettingRow, object?> apply) =>
        DependencyProperty.Register(name, type, typeof(SettingRow), new PropertyMetadata(null, (d, e) => apply((SettingRow)d, e.NewValue)));
}
