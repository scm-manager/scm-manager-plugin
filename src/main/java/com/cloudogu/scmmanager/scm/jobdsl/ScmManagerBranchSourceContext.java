package com.cloudogu.scmmanager.scm.jobdsl;

import com.cloudogu.scmmanager.scm.BranchDiscoveryTrait;
import com.cloudogu.scmmanager.scm.PullRequestDiscoveryTrait;
import com.cloudogu.scmmanager.scm.TagDiscoveryTrait;
import com.google.common.base.Strings;
import javaposse.jobdsl.dsl.Context;
import javaposse.jobdsl.dsl.Preconditions;
import jenkins.scm.api.trait.SCMSourceTrait;

import java.util.ArrayList;
import java.util.List;

public class ScmManagerBranchSourceContext implements Context {

  private String id;
  private String serverUrl;
  private String repository;
  private String credentialsId;

  private boolean discoverBranches = true;
  private boolean discoverPullRequest = true;
  private boolean discoverTags = false;

  public String getId() {
    return id;
  }

  public void id(String id) {
    this.id = id;
  }

  public String getServerUrl() {
    return serverUrl;
  }

  public void serverUrl(String serverUrl) {
    this.serverUrl = serverUrl;
  }

  public String getRepository() {
    return repository;
  }

  public void repository(String repository) {
    this.repository = repository;
  }

  public String getCredentialsId() {
    return credentialsId;
  }

  public void credentialsId(String credentialsId) {
    this.credentialsId = credentialsId;
  }

  public void discoverBranches(boolean discoverBranches) {
    this.discoverBranches = discoverBranches;
  }

  public void discoverPullRequest(boolean discoverPullRequest) {
    this.discoverPullRequest = discoverPullRequest;
  }

  public void discoverTags(boolean discoverTags) {
    this.discoverTags = discoverTags;
  }

  public List<SCMSourceTrait> getTraits() {
    List<SCMSourceTrait> traits = new ArrayList<>();
    if (discoverBranches) {
      traits.add(new BranchDiscoveryTrait());
    }
    if (discoverPullRequest) {
      traits.add(new PullRequestDiscoveryTrait());
    }
    if (discoverTags) {
      traits.add(new TagDiscoveryTrait());
    }
    return traits;
  }

  public void validate() {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(id), "id is required");
    Preconditions.checkArgument(!Strings.isNullOrEmpty(serverUrl), "serverUrl is required");
    Preconditions.checkArgument(!Strings.isNullOrEmpty(repository), "serverUrl is required");
  }
}
