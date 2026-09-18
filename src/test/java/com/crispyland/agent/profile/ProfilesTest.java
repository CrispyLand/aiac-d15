package com.crispyland.agent.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfilesTest {

    @TempDir
    Path root;

    @Test
    void aUserProfileIsReadFieldByFieldOutOfItsFile() throws IOException {
        write("users/russell.yml", """
                display-name: Russell
                language: Russian
                tone: formal
                format:
                  shape: short bullet points
                  max-words: 120
                never:
                  - use emoji
                  - open with pleasantries
                limits:
                  max-completion-tokens: 400
                  temperature: 0.2
                  reasoning-effort: low
                default-lens: economist""");

        UserProfile user = new Profiles(root).user("russell");

        assertThat(user.displayName()).isEqualTo("Russell");
        assertThat(user.language()).isEqualTo("Russian");
        assertThat(user.tone()).isEqualTo("formal");
        assertThat(user.format().shape()).isEqualTo("short bullet points");
        assertThat(user.format().maxWords()).isEqualTo(120);
        assertThat(user.never()).containsExactly("use emoji", "open with pleasantries");
        assertThat(user.limits()).isEqualTo(new Limits(400, 0.2, "low"));
        assertThat(user.defaultLens()).isEqualTo("economist");
    }

    @Test
    void theFilenameIsTheIdSoTwoFilesCannotClaimTheSameOne() throws IOException {
        // The switcher posts an id back; if a file could name itself anything, two of them could
        // answer to the same name and which one you got would depend on directory order.
        write("users/dana.yml", "display-name: Dana");

        assertThat(new Profiles(root).user("dana").id()).isEqualTo("dana");
    }

    @Test
    void aMalformedFileCostsItsOwnerTheirProfileAndNobodyElseTheirs() throws IOException {
        // These files are hand-edited by the person being personalized for. One bad edit must not
        // be able to take the service down for every other user.
        write("users/broken.yml", "language: [unclosed");
        write("users/dana.yml", "display-name: Dana");

        assertThat(new Profiles(root).users()).extracting(UserProfile::id).containsExactly("dana");
    }

    @Test
    void yamlTagsCannotInstantiateArbitraryClasses() throws IOException {
        // Parsed with SafeConstructor. A profile directory is a boundary — the one agent input
        // that is neither code nor a message — so a tag naming a class is refused, not honoured.
        write("users/evil.yml", "display-name: !!java.io.File [/etc/passwd]");

        assertThat(new Profiles(root).users()).isEmpty();
    }

    @Test
    void aMissingDirectoryLeavesEveryoneUnprofiledRatherThanBreakingTheApp() {
        Profiles profiles = new Profiles(root.resolve("absent"));

        assertThat(profiles.users()).isEmpty();
        assertThat(profiles.lenses()).isEmpty();
        assertThat(profiles.user("russell")).isNull();
    }

    @Test
    void aSingleRuleMayBeWrittenWithoutBrackets() throws IOException {
        write("users/dana.yml", "never: use bullet points");

        assertThat(new Profiles(root).user("dana").never()).containsExactly("use bullet points");
    }

    @Test
    void aLensKeywordMatchReportsTheWordThatCausedItNotJustThatItMatched() throws IOException {
        // "Chose economist" is not reviewable; "chose economist on 'инфляц'" is. When selection
        // picks something absurd, the word that caused it is the only useful thing to know.
        write("lenses/economist.yml", """
                title: Economist
                keywords: [inflation, инфляц]""");

        Lens lens = new Profiles(root).lens("economist");

        assertThat(lens.claim("Что будет с инфляцией?")).isEqualTo("инфляц");
        assertThat(lens.claim("Why is INFLATION high?")).isEqualTo("inflation");
        assertThat(lens.claim("how do I bake bread")).isNull();
    }

    @Test
    void theSeededProfilesShippedWithTheRepoAllParse() {
        // A typo in a seed file is invisible until someone selects it and silently gets nothing.
        Profiles shipped = new Profiles(Path.of("profiles"));

        assertThat(shipped.users()).extracting(UserProfile::id).contains("russell", "dana");
        assertThat(shipped.lenses()).extracting(Lens::id)
                .contains("chemist", "psychologist", "economist");
        assertThat(shipped.lenses()).allSatisfy(lens -> {
            assertThat(lens.lens()).isNotBlank();
            assertThat(lens.forbids()).isNotEmpty();
            assertThat(lens.keywords()).isNotEmpty();
        });
        assertThat(shipped.user("russell").language()).isEqualTo("Russian");
        assertThat(shipped.user("dana").language()).isEqualTo("English");
        // Any seed profile with a tight ceiling must also cap its thinking, or the reply it gets
        // back is a truncated chain of thought. Asserted on the shipped files because this is a
        // property of the pair of numbers, not of the loader.
        assertThat(shipped.users()).allSatisfy(user -> {
            Integer ceiling = user.limits().maxCompletionTokens();
            if (ceiling != null && ceiling < 800) {
                assertThat(user.limits().reasoningEffort())
                        .as("%s caps the reply at %d and must cap reasoning too", user.id(), ceiling)
                        .isEqualTo("low");
            }
        });
    }

    @Test
    void lensesAreListedInAStableOrderSoTheSwitcherDoesNotReshuffle() throws IOException {
        write("lenses/zebra.yml", "title: Z");
        write("lenses/alpha.yml", "title: A");

        assertThat(new Profiles(root).lenses()).extracting(Lens::id)
                .isEqualTo(List.of("alpha", "zebra"));
    }

    private void write(String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
