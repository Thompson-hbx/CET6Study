using Inkwell.Core.Teacher;

namespace Inkwell.Core.Tests;

public sealed class PlacementTest
{
    [Fact]
    public void ForwardPlainOrCaseMatchBeatsEarlierExactWrap()
    {
        const string curlyText = "I don't care. Wait: I don’t know.";
        Assert.Equal([14, 22], Placement.Locate(curlyText, [M(1, "Wait"), M(2, "don't")]));

        const string caseText = "The cat sat. Wait: the cat ran.";
        Assert.Equal([13, 19], Placement.Locate(caseText, [M(1, "Wait"), M(2, "The cat")]));
    }

    [Fact]
    public void WrapAroundDoesNotMoveCursorBackward()
    {
        const string text = "Alpha is here. Beta is there. Gamma is last.";
        Assert.Equal([15, 0, 20], Placement.Locate(text, [M(1, "Beta"), M(2, "Alpha"), M(3, "is")]));
    }

    [Fact]
    public void WrapAroundStillFindsEarlierUntakenMatchWhenNothingFollowsCursor()
    {
        const string text = "First one. Second two.";
        Assert.Equal([11, 0], Placement.Locate(text, [M(1, "Second"), M(2, "First")]));
    }

    [Fact]
    public void EmptyQuoteReturnsNull() =>
        Assert.Equal([null], Placement.Locate("Some text.", [M(1, "")]));

    private static Mark M(int n, string quote, string fix = "") => new(n, quote, fix, "", Severity.Must, "语法");
}
