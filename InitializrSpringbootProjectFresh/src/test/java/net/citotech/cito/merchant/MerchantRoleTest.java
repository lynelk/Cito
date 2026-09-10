package net.citotech.cito.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Covers audit N7's capability matrix: OWNER has full access, FINANCE can move money but not manage
 * users/channels, DEVELOPER can manage channels but not move money, VIEWER can only view. Also
 * covers the fail-open contract of {@link MerchantRole#fromString(String)}: a null or unrecognized
 * stored role value must resolve to OWNER, never to a more restrictive role such as VIEWER, so a
 * pre-migration/unknown row never silently locks out an already-active merchant user.
 */
class MerchantRoleTest {

    @Test
    void ownerCanDoEverything() {
        assertThat(MerchantRole.OWNER.canManageUsers()).isTrue();
        assertThat(MerchantRole.OWNER.canManageChannels()).isTrue();
        assertThat(MerchantRole.OWNER.canInitiatePayouts()).isTrue();
        assertThat(MerchantRole.OWNER.canViewStatements()).isTrue();
    }

    @Test
    void financeCanMoveMoneyAndViewButNotManageUsersOrChannels() {
        assertThat(MerchantRole.FINANCE.canManageUsers()).isFalse();
        assertThat(MerchantRole.FINANCE.canManageChannels()).isFalse();
        assertThat(MerchantRole.FINANCE.canInitiatePayouts()).isTrue();
        assertThat(MerchantRole.FINANCE.canViewStatements()).isTrue();
    }

    @Test
    void developerCanManageChannelsButNotMoveMoneyOrManageUsers() {
        assertThat(MerchantRole.DEVELOPER.canManageUsers()).isFalse();
        assertThat(MerchantRole.DEVELOPER.canManageChannels()).isTrue();
        assertThat(MerchantRole.DEVELOPER.canInitiatePayouts()).isFalse();
        assertThat(MerchantRole.DEVELOPER.canViewStatements()).isTrue();
    }

    @Test
    void viewerIsReadOnly() {
        assertThat(MerchantRole.VIEWER.canManageUsers()).isFalse();
        assertThat(MerchantRole.VIEWER.canManageChannels()).isFalse();
        assertThat(MerchantRole.VIEWER.canInitiatePayouts()).isFalse();
        assertThat(MerchantRole.VIEWER.canViewStatements()).isTrue();
    }

    @Test
    void fromStringParsesValidValuesCaseInsensitivelyAndTrimmed() {
        assertThat(MerchantRole.fromString("FINANCE")).isEqualTo(MerchantRole.FINANCE);
        assertThat(MerchantRole.fromString("developer")).isEqualTo(MerchantRole.DEVELOPER);
        assertThat(MerchantRole.fromString("  Viewer  ")).isEqualTo(MerchantRole.VIEWER);
        assertThat(MerchantRole.fromString("Owner")).isEqualTo(MerchantRole.OWNER);
    }

    @Test
    void fromStringFailsClosedToViewerOnNullValue() {
        assertThat(MerchantRole.fromString(null)).isEqualTo(MerchantRole.VIEWER);
    }

    @Test
    void fromStringFailsClosedToViewerOnBlankValue() {
        assertThat(MerchantRole.fromString("")).isEqualTo(MerchantRole.VIEWER);
        assertThat(MerchantRole.fromString("   ")).isEqualTo(MerchantRole.VIEWER);
    }

    @Test
    void fromStringFailsClosedToViewerOnUnrecognizedValue() {
        // e.g. a future role name this build doesn't know about, or corrupted data - must never
        // silently downgrade to a more restrictive role like VIEWER.
        assertThat(MerchantRole.fromString("SUPERADMIN")).isEqualTo(MerchantRole.VIEWER);
        assertThat(MerchantRole.fromString("not-a-role")).isEqualTo(MerchantRole.VIEWER);
    }
}
