package com.sysdig.jenkins.plugins.sysdig.scanner;

import com.sysdig.jenkins.plugins.sysdig.BuildConfig;
import com.sysdig.jenkins.plugins.sysdig.SysdigBuilder;
import com.sysdig.jenkins.plugins.sysdig.containerrunner.Container;
import com.sysdig.jenkins.plugins.sysdig.containerrunner.ContainerRunner;
import com.sysdig.jenkins.plugins.sysdig.containerrunner.ContainerRunnerFactory;
import com.sysdig.jenkins.plugins.sysdig.log.SysdigLogger;
import hudson.EnvVars;
import net.sf.json.JSONObject;
import org.junit.*;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.quality.Strictness;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class InlineScannerRemoteExecutorTests {
  private static final String SCAN_IMAGE = "quay.io/sysdig/secure-inline-scan:2";
  private static final String IMAGE_TO_SCAN = "foo:latest";
  private static final String SYSDIG_TOKEN = "foo-token";

  private InlineScannerRemoteExecutor scannerRemoteExecutor = null;

  //TODO: Throw exception on container run

  //TODO: Handle errors on plugin execution

  //TODO: Handle failed scan, arg errors, other errors

  @Rule
  public MockitoRule rule = MockitoJUnit.rule().strictness(Strictness.STRICT_STUBS);

  ContainerRunner containerRunner;
  private Container container;
  private JSONObject outputObject;
  private String logOutput;
  private SysdigLogger logger;
  private EnvVars nodeEnvVars;
  private BuildConfig config;

  @Before
  public void beforeEach() throws InterruptedException {
    config = mock(BuildConfig.class);
    when(config.getSysdigToken()).thenReturn(SYSDIG_TOKEN);
    when(config.getEngineTLSVerify()).thenReturn(true);
    // new String in here is not redundant, as we want to make sure that internally we compare by value, not by ref
    when(config.getEngineURL()).thenReturn(new String(SysdigBuilder.DescriptorImpl.DEFAULT_ENGINE_URL));
    when(config.getDebug()).thenReturn(false);

    containerRunner = mock(ContainerRunner.class);
    ContainerRunnerFactory containerRunnerFactory = mock (ContainerRunnerFactory.class);
    when(containerRunnerFactory.getContainerRunner(any())).thenReturn(containerRunner);

    nodeEnvVars = new EnvVars();
    logger = mock(SysdigLogger.class);
    InlineScannerRemoteExecutor.setContainerRunnerFactory(containerRunnerFactory);
    scannerRemoteExecutor = new InlineScannerRemoteExecutor(
      IMAGE_TO_SCAN,
      null,
      config,
      logger,
      nodeEnvVars);

    container = mock(Container.class);
    outputObject = new JSONObject();
    logOutput = "";

    // Mock container creation to return our mock
    doReturn(container).when(containerRunner).createContainer(any(), any(), any(), any(), any());
    // Mock execution of the touch or mkdir commands
    doNothing().when(container).exec(
      argThat(args -> args.get(0).equals("touch") || args.get(0).equals("mkdir")),
      any(),
      any(),
      any());

    // Mock async executions of "tail", to simulate some log output
    doNothing().when(container).execAsync(
      argThat(args -> args.get(0).equals("tail")),
      any(),
      argThat(matcher -> {
        matcher.accept(logOutput);
        return true;
      }),
      any()
    );

  }

  private void execInlineScanDoesNothing() throws InterruptedException {
    // Mock sync execution of the inline scan script. Mock the JSON output
    doNothing().when(container).exec(
      argThat(args -> args.get(0).equals("/sysdig-inline-scan.sh")),
      any(),
      argThat(matcher -> {
        matcher.accept(outputObject.toString());
        return true;
      }),
      any()
    );
  }

  @Test
  public void containerIsCreatedAndExecuted() throws Exception {
    // Given
    execInlineScanDoesNothing();

    // When
    scannerRemoteExecutor.call();

    // Then
    verify(containerRunner, times(1)).createContainer(
      eq(SCAN_IMAGE),
      argThat(args -> args.contains("cat")),
      any(),
      any(),
      any());

    verify(container, times(1)).runAsync(any(), any());

    verify(container, times(1)).exec(
      argThat(args -> args.contains("/sysdig-inline-scan.sh")),
      isNull(),
      any(),
      any());
  }

  @Test
  public void containerDoesNotHaveAnyAdditionalParameters() throws Exception {
    // Given
    execInlineScanDoesNothing();

    // When
    scannerRemoteExecutor.call();

    // Then
    verify(containerRunner, times(1)).createContainer(
      eq(SCAN_IMAGE),
      argThat(args -> args.contains("cat")),
      any(),
      any(),
      any());

    verify(container, never()).exec(
      argThat(args -> args.stream().anyMatch(Pattern.compile("^(--verbose|-v|-s|--sysdig-url|-o|--on-prem|-f|--dockerfile|--sysdig-skip-tls)$").asPredicate()) ),
      isNull(),
      any(),
      any());
  }

  @Test
  public void dockerSocketIsMounted() throws Exception {
    // Given
    execInlineScanDoesNothing();

    // When
    scannerRemoteExecutor.call();

    // Then
    verify(containerRunner, times(1)).createContainer(
      eq(SCAN_IMAGE),
      argThat(args -> args.contains("cat")),
      any(),
      any(),
      argThat(args -> args.contains("/var/run/docker.sock:/var/run/docker.sock")));
  }

  @Test
  public void logOutputIsSentToTheLogger() throws Exception {
    // Given
    logOutput = "foo-output";
    execInlineScanDoesNothing();

    // When
    scannerRemoteExecutor.call();

    // Then
    verify(logger, atLeastOnce()).logInfo(argThat(msg -> msg.contains("foo-output")));
  }

  @Test
  public void scanJSONOutputIsReturned() throws Exception {
    // Given
    outputObject.put("foo-key", "foo-value");
    execInlineScanDoesNothing();

    // When
    String scanOutput = scannerRemoteExecutor.call();

    // Then
    assertEquals(scanOutput, outputObject.toString());
  }

  @Test
  public void addedByAnnotationsAreIncluded() throws Exception {
    // Given
    execInlineScanDoesNothing();

    // When
    scannerRemoteExecutor.call();

    // Then
    verify(containerRunner, times(1)).createContainer(
      any(),
      any(),
      any(),
      argThat(env -> env.contains("SYSDIG_ADDED_BY=cicd-inline-scan")),
      any());
  }

  @Test
  public void containerExecutionContainsExpectedParameters() throws Exception {
    // Given
    execInlineScanDoesNothing();

    // When
    scannerRemoteExecutor.call();

    // Then
    verify(container, times(1)).exec(argThat(args -> args.contains("--format=JSON")), isNull(), any(), any());
    verify(container, times(1)).exec(argThat(args -> args.contains(IMAGE_TO_SCAN)), isNull(), any(), any());
  }

  @Test
  public void customURLIsProvidedAsParameter() throws Exception {
    // Given
    execInlineScanDoesNothing();
    when(config.getEngineURL()).thenReturn("https://my-foo-url");

    // When
    scannerRemoteExecutor.call();

    // Then
    verify(container, times(1)).exec(argThat(args -> args.contains("--sysdig-url=https://my-foo-url")), isNull(), any(), any());
    verify(container, times(1)).exec(argThat(args -> args.contains("--on-prem")), isNull(), any(), any());
  }

  @Test
  public void verboseIsEnabledWhenDebug() throws Exception {
    // Given
    execInlineScanDoesNothing();
    when(config.getDebug()).thenReturn(true);

    // When
    scannerRemoteExecutor.call();

    // Then
    verify(container, times(1)).exec(argThat(args -> args.contains("--verbose")), isNull(), any(), any());
  }

  @Test
  public void skipTLSFlagWhenInsecure() throws Exception {
    // Given
    execInlineScanDoesNothing();
    when(config.getEngineTLSVerify()).thenReturn(false);

    // When
    scannerRemoteExecutor.call();

    // Then
    verify(container, times(1)).exec(argThat(args -> args.contains("--sysdig-skip-tls")), isNull(), any(), any());
  }

  @Test
  public void dockerfileIsProvidedAsParameter() throws Exception {
    // Given
    execInlineScanDoesNothing();
    scannerRemoteExecutor = new InlineScannerRemoteExecutor(IMAGE_TO_SCAN, "/tmp/foo-dockerfile", config, logger, nodeEnvVars);

    // When
    scannerRemoteExecutor.call();

    // Then
    verify(container, times(1)).exec(argThat(args -> args.contains("--dockerfile=/tmp/Dockerfile")), isNull(), any(), any());
  }

  @Test
  public void dockerfileIsMountedAtTmp() throws Exception {
    // Given
    execInlineScanDoesNothing();
    scannerRemoteExecutor = new InlineScannerRemoteExecutor(IMAGE_TO_SCAN, "/tmp/foo-dockerfile", config, logger, nodeEnvVars);

    // When
    scannerRemoteExecutor.call();

    verify(containerRunner, times(1)).createContainer(
      eq(SCAN_IMAGE),
      any(),
      any(),
      any(),
      argThat(arg -> arg.contains("/tmp/foo-dockerfile:/tmp/Dockerfile")));
  }


  @Test
  public void setSysdigTokenIsProvidedAsEnvironmentVariable() throws Exception {
    // Given
    execInlineScanDoesNothing();

    // When
    scannerRemoteExecutor.call();

    // Then
    verify(containerRunner, times(1)).createContainer(
      any(),
      any(),
      any(),
      argThat(env -> env.contains("SYSDIG_API_TOKEN=" + SYSDIG_TOKEN)),
      any());
  }

  @Test
  public void applyProxyEnvVarsFrom_http_proxy() throws Exception {
    // Given
    execInlineScanDoesNothing();
    nodeEnvVars.put("http_proxy", "http://httpproxy:1234");

    // When
    scannerRemoteExecutor.call();

    // Then
    verify(containerRunner, times(1)).createContainer(
      any(),
      any(),
      any(),
      argThat(env -> env.contains("http_proxy=http://httpproxy:1234")),
      any());
    verify(containerRunner, times(1)).createContainer(
      any(),
      any(),
      any(),
      argThat(env -> env.contains("https_proxy=http://httpproxy:1234")),
      any());
  }

  @Test
  public void applyProxyEnvVarsFrom_https_proxy() throws Exception {
    // Given
    execInlineScanDoesNothing();
    nodeEnvVars.put("http_proxy", "http://httpproxy:1234");
    nodeEnvVars.put("https_proxy", "http://httpsproxy:1234");

    // When
    scannerRemoteExecutor.call();

    // Then
    verify(containerRunner, times(1)).createContainer(
      any(),
      any(),
      any(),
      argThat(env -> env.contains("http_proxy=http://httpproxy:1234")),
      any());
    verify(containerRunner, times(1)).createContainer(
      any(),
      any(),
      any(),
      argThat(env -> env.contains("https_proxy=http://httpsproxy:1234")),
      any());
  }

  @Test
  public void applyProxyEnvVarsFrom_no_proxy() throws Exception {
    // Given
    execInlineScanDoesNothing();
    nodeEnvVars.put("no_proxy", "1.2.3.4,5.6.7.8");

    // When
    scannerRemoteExecutor.call();

    // Then
    verify(containerRunner, times(1)).createContainer(
      any(),
      any(),
      any(),
      argThat(env -> env.contains("no_proxy=1.2.3.4,5.6.7.8")),
      any());
  }

  @Test
  public void interruptThreadAbortsClient() throws InterruptedException {
    // Given
    doAnswer(invocation -> { Thread.sleep(10000); return null; }).
      when(container).exec(
      argThat(args -> args.get(0).equals("/sysdig-inline-scan.sh")),
      any(),
      any(),
      any());

    Thread t = Thread.currentThread();
    ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    executor.schedule(t::interrupt , 1, TimeUnit.SECONDS);

    // When
    assertThrows(
      InterruptedException.class,
      () -> scannerRemoteExecutor.call());
  }
}
