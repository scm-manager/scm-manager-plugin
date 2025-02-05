package com.cloudogu.scmmanager.scm;

import com.cloudogu.scmmanager.scm.api.ScmManagerApi;
import com.cloudogu.scmmanager.scm.api.ScmManagerApiFactory;
import com.cloudogu.scmmanager.scm.api.ScmManagerHead;
import com.cloudogu.scmmanager.scm.api.ScmManagerObservable;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Action;
import hudson.model.TaskListener;
import hudson.scm.SCM;
import jenkins.scm.api.SCMEvent;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMHeadCategory;
import jenkins.scm.api.SCMHeadEvent;
import jenkins.scm.api.SCMHeadObserver;
import jenkins.scm.api.SCMProbe;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceCriteria;
import jenkins.scm.api.SCMSourceEvent;
import jenkins.scm.api.trait.SCMSourceRequest;
import jenkins.scm.api.trait.SCMSourceTrait;
import jenkins.scm.api.trait.SCMSourceTraitDescriptor;
import jenkins.scm.impl.ChangeRequestSCMHeadCategory;
import jenkins.scm.impl.TagSCMHeadCategory;
import jenkins.scm.impl.UncategorizedSCMHeadCategory;
import jenkins.scm.impl.form.NamedArrayList;
import jenkins.scm.impl.trait.Discovery;
import jenkins.scm.impl.trait.Selection;
import jenkins.util.NonLocalizable;
import lombok.Getter;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;

public class ScmManagerSource extends SCMSource {

  @Getter
  private final String serverUrl;
  private final String namespace;
  private final String name;
  private String type = null;

  @Getter
  private final String credentialsId;

  private LinkBuilder linkBuilder;

  private static final Logger LOG = LoggerFactory.getLogger(ScmManagerSource.class);

  @NonNull
  private List<SCMSourceTrait> traits = new ArrayList<>();

  // older versions do not have an api factory, if they are unmarshalled from disk
  // in order to support older versions we have to create the factory on demand
  @CheckForNull
  private ScmManagerApiFactory apiFactory;

  @DataBoundConstructor
  public ScmManagerSource(String serverUrl, String repository, String credentialsId) {
    this(serverUrl, repository, credentialsId, new ScmManagerApiFactory());
  }

  ScmManagerSource(
    String serverUrl,
    String repository,
    String credentialsId,
    ScmManagerApiFactory apiFactory
  ) {
    this.serverUrl = serverUrl;
    this.credentialsId = credentialsId;

    String[] parts = repository.split("/| \\(|\\)");
    this.namespace = parts[0];
    this.name = parts[1];
    this.apiFactory = apiFactory;

    if (parts.length > 2) {
      throw new IllegalArgumentException("Repositories must not contain a slash!");
    }

    LOG.debug("Created ScmManagerSource {}/{}", this.namespace, this.name);
  }

  @NonNull
  @Override
  public List<SCMSourceTrait> getTraits() {
    return Collections.unmodifiableList(traits);
  }

  @Override
  @DataBoundSetter
  public void setTraits(@CheckForNull List<SCMSourceTrait> traits) {
    this.traits = new ArrayList<>(Util.fixNull(traits));
  }

  String getNamespace() {
    return namespace;
  }

  String getName() {
    return name;
  }

  /**
   * Since the type can only be fetched after the construction of {@link ScmManagerSource}, types must
   * be accessed via this getter.
   *
   * @return Type
   */
  String getType() {
    if (this.type == null) {
      try {
        this.type = createApi().getRepository(namespace, name).get().getType();
      } catch (InterruptedException | ExecutionException e) {
        throw new RuntimeException(
          String.format("Type of repository %s/%s could not be loaded.",
            this.namespace, this.name), e);
      }
    }
    return this.type;
  }

  @Override
  protected void retrieve(SCMSourceCriteria criteria, @NonNull SCMHeadObserver observer, SCMHeadEvent<?> event, @NonNull TaskListener listener) throws IOException, InterruptedException {
    try (ScmManagerSourceRequest request = new ScmManagerSourceContext(criteria, observer)
      .withTraits(traits)
      .newRequest(this, listener)) {
      handleRequest(observer, event, request);
    }
  }

  @VisibleForTesting
  void handleRequest(@NonNull SCMHeadObserver observer, SCMHeadEvent<?> event, ScmManagerSourceRequest request) throws InterruptedException, IOException {
    LOG.debug("Handle request {}", request);
    Iterable<ScmManagerObservable> candidates = null;

    ScmManagerSourceRetriever handler = ScmManagerSourceRetriever.create(
      createApi(),
      namespace,
      name,
      traits
    );

    // for now we trigger a full scan for deletions
    // TODO improve handling of deletions
    if (event == null || event.getType() != SCMEvent.Type.REMOVED) {
      LOG.debug("Head event is null or not 'removed'");
      Set<SCMHead> includes = observer.getIncludes();
      if (includes != null && includes.size() == 1) {
        LOG.debug("Pick specific candidate");
        candidates = handler.getSpecificCandidatesFromSourceControl(request, includes.iterator().next());
      }
    }

    if (candidates == null) {
      LOG.debug("Pick list of candidates");
      candidates = handler.getAllCandidatesFromSourceControl(request);
      request.prepareForFullScan(candidates);
    }
    for (ScmManagerObservable candidate : candidates) {
      if (request.process(candidate.head(), candidate.revision(), handler::probe, new CriteriaWitness(request))) {
        return;
      }
    }
  }

  @NonNull
  @Override
  protected SCMProbe createProbe(@NonNull SCMHead head, @CheckForNull SCMRevision revision) {
    ScmManagerSourceRetriever handler = ScmManagerSourceRetriever.create(createApi(), namespace, name, traits);
    return handler.probe(head, revision);
  }

  private ScmManagerApi createApi() {
    if (apiFactory == null) {
      apiFactory = new ScmManagerApiFactory();
    }
    return apiFactory.create(getOwner(), serverUrl, credentialsId);
  }

  @NonNull
  @Override
  public SCM build(@NonNull SCMHead head, SCMRevision revision) {
    if (head instanceof ScmManagerHead scmManagerHead) {
      SCMBuilderProvider.Context ctx = new SCMBuilderProvider.Context(
        getLinkBuilder(),
        scmManagerHead,
        revision,
        credentialsId
      );
      return SCMBuilderProvider.from(ctx).withTraits(traits).build();
    }
    throw new IllegalArgumentException("Could not handle unknown SCMHead: " + head);
  }

  public String getRepository() {
    return String.format("%s/%s", namespace, name);
  }

  static final String ICON_SCM_MANAGER_LINK = "icon-scm-manager-link";

  static {
    Icons.register(ICON_SCM_MANAGER_LINK);
  }

  @NonNull
  @Override
  protected List<Action> retrieveActions(@NonNull SCMRevision revision, SCMHeadEvent event, @NonNull TaskListener listener) {
    return Collections.singletonList(
      new ScmManagerLink(ICON_SCM_MANAGER_LINK, getLinkBuilder().create(revision))
    );
  }

  @NonNull
  @Override
  protected List<Action> retrieveActions(@NonNull SCMHead head, SCMHeadEvent event, @NonNull TaskListener listener) {
    return Collections.singletonList(
      new ScmManagerLink(ICON_SCM_MANAGER_LINK, getLinkBuilder().create(head))
    );
  }

  @NonNull
  @Override
  protected List<Action> retrieveActions(@CheckForNull SCMSourceEvent event, @NonNull TaskListener listener) {
    return Collections.singletonList(
      new ScmManagerLink(ICON_SCM_MANAGER_LINK, getLinkBuilder().repo())
    );
  }

  @Override
  protected boolean isCategoryEnabled(@NonNull SCMHeadCategory category) {
    return isCategoryTraitEnabled(category) && SCMBuilderProvider.byType(getType()).isSupported(category);
  }

  @VisibleForTesting
  boolean isCategoryTraitEnabled(@NonNull SCMHeadCategory category) {
    Class<? extends SCMSourceTrait> traitClass = getTraitForCategory(category);
    return isTraitEnabled(traitClass);
  }

  private boolean isTraitEnabled(Class<? extends SCMSourceTrait> traitClass) {
    return getTraits().stream().anyMatch(t -> traitClass.isAssignableFrom(t.getClass()));
  }

  private Class<? extends SCMSourceTrait> getTraitForCategory(SCMHeadCategory category) {
    if (category instanceof TagSCMHeadCategory) {
      return TagDiscoveryTrait.class;
    } else if (category instanceof ChangeRequestSCMHeadCategory) {
      return PullRequestDiscoveryTrait.class;
    }
    return ScmManagerBranchDiscoveryTrait.class;
  }

  private LinkBuilder getLinkBuilder() {
    if (linkBuilder == null) {
      linkBuilder = new LinkBuilder(createApi().getBaseUrl(), namespace, name);
    }
    return linkBuilder;
  }

  public String getRemoteUrl() {
    return new LinkBuilder(serverUrl, namespace, name).repo();
  }

  @Extension
  @Symbol("scmManager")
  public static class DescriptorImpl extends ScmManagerSourceDescriptor {

    public DescriptorImpl() {
      super(new ScmManagerApiFactory(), SCMBuilderProvider::isSupported);
    }

    @Nonnull
    @Override
    public String getDisplayName() {
      List<String> typeList = SCMBuilderProvider.all()
        .stream()
        .map(SCMBuilderProvider::getType)
        .toList();
      String types = Joiner.on(", ").join(typeList);
      return String.format("SCM-Manager (%s)", types);
    }

    @SuppressWarnings({"unused", "java:S1452"}) // used By stapler / wildcard issue unavoidable due to Jenkins class
    public List<NamedArrayList<? extends SCMSourceTraitDescriptor>> getTraitsDescriptorLists() {
      // we use a LinkedHashSet to deduplicate and keep order
      List<SCMSourceTraitDescriptor> all = findAllAvailableTraits();
      List<NamedArrayList<? extends SCMSourceTraitDescriptor>> result = new ArrayList<>();
      NamedArrayList.select(
        all,
        "Within repository",
        NamedArrayList.anyOf(NamedArrayList.withAnnotation(Discovery.class), NamedArrayList.withAnnotation(Selection.class)),
        true,
        result
      );
      int insertionPoint = result.size();
      for (SCMBuilderProvider provider : SCMBuilderProvider.all()) {
        NamedArrayList.select(all, provider.getDisplayName(), it -> provider.getScmClass().isAssignableFrom(it.getScmClass()), true, result);
      }
      NamedArrayList.select(all, "General", null, true, result, insertionPoint);
      return result;
    }

    @NonNull
    private List<SCMSourceTraitDescriptor> findAllAvailableTraits() {
      Set<SCMSourceTraitDescriptor> dedup = new LinkedHashSet<>();
      dedup.addAll(SCMSourceTrait._for(this, ScmManagerSourceContext.class, null));
      for (SCMBuilderProvider provider : SCMBuilderProvider.all()) {
        dedup.addAll(provider.getTraitDescriptors(this));
      }
      return new ArrayList<>(dedup);
    }

    @Override
    @NonNull
    public List<SCMSourceTrait> getTraitsDefaults() {
      return Arrays.asList(
        new ScmManagerBranchDiscoveryTrait(),
        new PullRequestDiscoveryTrait(false)
      );
    }

    @NonNull
    @Override
    protected SCMHeadCategory[] createCategories() {
      return new SCMHeadCategory[]{
        UncategorizedSCMHeadCategory.DEFAULT,
        // TODO do we have to localize it
        new ChangeRequestSCMHeadCategory(new NonLocalizable("Pull Requests")),
        TagSCMHeadCategory.DEFAULT
      };
    }

  }

  private record CriteriaWitness(ScmManagerSourceRequest request) implements SCMSourceRequest.Witness {

    @Override
    @SuppressWarnings("java:S6213") // could not be changed yet due to Jenkins class
    public void record(@NonNull SCMHead scmHead, SCMRevision revision, boolean isMatch) {
      PrintStream logger = request.listener().getLogger();
      logger.append("    ").append(scmHead.getName()).append(": ");
      if (revision == null) {
        logger.println("Skipped");
      } else {
        if (isMatch) {
          logger.println("Met criteria");
        } else {
          logger.println("Does not meet criteria");
        }
      }
    }

  }

}
