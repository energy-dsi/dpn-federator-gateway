package uk.gov.dbt.ndtp.federator.common.service.secret;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bettercloud.vault.Vault;
import com.bettercloud.vault.VaultConfig;
import com.bettercloud.vault.api.Auth;
import com.bettercloud.vault.response.AuthResponse;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class VaultTokenRenewalManagerTest {

    @Test
    void start_doesNothing_forTokenAuth() {
        Vault vault = mock(Vault.class);
        VaultConfig config = mock(VaultConfig.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);

        VaultAuthConfig authConfig = VaultAuthConfig.forToken("root-token");
        VaultTokenRenewalManager manager = new VaultTokenRenewalManager(vault, config, authConfig, scheduler);

        manager.start();

        verifyNoInteractions(scheduler);
    }

    @Test
    void start_schedulesRenewal_forAppRoleAuth() {
        Vault vault = mock(Vault.class);
        VaultConfig config = mock(VaultConfig.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);

        VaultAuthConfig authConfig = VaultAuthConfig.forAppRole("role-id", "secret-id", "approle", 120L);
        VaultTokenRenewalManager manager = new VaultTokenRenewalManager(vault, config, authConfig, scheduler);

        manager.start();

        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(120L), eq(120L), eq(TimeUnit.SECONDS));
    }

    @Test
    void shutdown_stopsScheduler() {
        Vault vault = mock(Vault.class);
        VaultConfig config = mock(VaultConfig.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);

        VaultAuthConfig authConfig = VaultAuthConfig.forAppRole("role-id", "secret-id");
        VaultTokenRenewalManager manager = new VaultTokenRenewalManager(vault, config, authConfig, scheduler);

        manager.shutdown();

        verify(scheduler).shutdown();
    }

    @Test
    void renewalTask_renewsToken_whenRenewSelfSucceeds() throws Exception {
        Vault vault = mock(Vault.class);
        Auth auth = mock(Auth.class);
        VaultConfig config = mock(VaultConfig.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        AuthResponse renewResponse = mock(AuthResponse.class);

        when(vault.auth()).thenReturn(auth);
        when(auth.renewSelf()).thenReturn(renewResponse);
        when(renewResponse.getAuthLeaseDuration()).thenReturn(3600L);

        VaultAuthConfig authConfig = VaultAuthConfig.forAppRole("role-id", "secret-id");
        VaultTokenRenewalManager manager = new VaultTokenRenewalManager(vault, config, authConfig, scheduler);

        Runnable task = captureScheduledTask(manager, scheduler);
        task.run();

        verify(auth).renewSelf();
        verify(auth, never()).loginByAppRole(any(), any(), any());
        verify(config, never()).token(any());
    }

    @Test
    void renewalTask_reLogsIn_whenRenewSelfFails() throws Exception {
        Vault vault = mock(Vault.class);
        Auth auth = mock(Auth.class);
        VaultConfig config = mock(VaultConfig.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        AuthResponse loginResponse = mock(AuthResponse.class);

        when(vault.auth()).thenReturn(auth);
        when(auth.renewSelf()).thenThrow(new RuntimeException("token expired"));
        when(auth.loginByAppRole("approle", "role-id", "secret-id")).thenReturn(loginResponse);
        when(loginResponse.getAuthClientToken()).thenReturn("new-token");
        when(loginResponse.getAuthLeaseDuration()).thenReturn(3600L);

        VaultAuthConfig authConfig = VaultAuthConfig.forAppRole("role-id", "secret-id");
        VaultTokenRenewalManager manager = new VaultTokenRenewalManager(vault, config, authConfig, scheduler);

        Runnable task = captureScheduledTask(manager, scheduler);
        task.run();

        verify(auth).loginByAppRole("approle", "role-id", "secret-id");
        verify(config).token("new-token");
    }

    @Test
    void renewalTask_logsError_whenRenewAndReLoginBothFail() throws Exception {
        Vault vault = mock(Vault.class);
        Auth auth = mock(Auth.class);
        VaultConfig config = mock(VaultConfig.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);

        when(vault.auth()).thenReturn(auth);
        when(auth.renewSelf()).thenThrow(new RuntimeException("token expired"));
        when(auth.loginByAppRole(any(), any(), any())).thenThrow(new RuntimeException("vault unreachable"));

        VaultAuthConfig authConfig = VaultAuthConfig.forAppRole("role-id", "secret-id");
        VaultTokenRenewalManager manager = new VaultTokenRenewalManager(vault, config, authConfig, scheduler);

        Runnable task = captureScheduledTask(manager, scheduler);

        assertDoesNotThrow(task::run);
        verify(config, never()).token(any());
    }

    private Runnable captureScheduledTask(VaultTokenRenewalManager manager, ScheduledExecutorService scheduler) {
        manager.start();
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleWithFixedDelay(captor.capture(), anyLong(), anyLong(), any(TimeUnit.class));
        return captor.getValue();
    }
}
