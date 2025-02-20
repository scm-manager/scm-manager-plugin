package com.cloudogu.scmmanager.info;

import hudson.Extension;
import java.util.Optional;
import jenkins.model.Jenkins;

@Extension(optional = true)
public class PullRequestJobInformationResolverProvider implements JobInformationResolverProvider {

    @Override
    public Optional<JobInformationResolver> get() {
        if (Jenkins.get().getPlugin("workflow-multibranch") != null
                && Jenkins.get().getPlugin("branch-api") != null
                // TODO PullRequestJobInformationResolver works only with git
                && Jenkins.get().getPlugin("git") != null) {
            return Optional.of(new PullRequestJobInformationResolver());
        }
        return Optional.empty();
    }
}
