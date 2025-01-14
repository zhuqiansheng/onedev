package io.onedev.server.model;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.stream.Collectors;

import javax.annotation.Nullable;
import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Index;
import javax.persistence.JoinColumn;
import javax.persistence.Lob;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.Table;
import javax.persistence.Version;
import javax.validation.ConstraintValidatorContext;
import javax.validation.Valid;
import javax.validation.ValidationException;

import org.apache.commons.lang3.SerializationUtils;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TagCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.LastCommitsOfChildren;
import org.eclipse.jgit.revwalk.LastCommitsOfChildren.Value;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.validator.constraints.NotEmpty;

import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;

import io.onedev.commons.launcher.loader.ListenerRegistry;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.LinearRange;
import io.onedev.commons.utils.LockUtils;
import io.onedev.commons.utils.StringUtils;
import io.onedev.commons.utils.stringmatch.ChildAwareMatcher;
import io.onedev.commons.utils.stringmatch.Matcher;
import io.onedev.server.OneDev;
import io.onedev.server.cache.CommitInfoManager;
import io.onedev.server.ci.CISpec;
import io.onedev.server.ci.DefaultCISpecProvider;
import io.onedev.server.ci.job.Job;
import io.onedev.server.ci.job.param.JobParam;
import io.onedev.server.ci.job.trigger.BranchUpdateTrigger;
import io.onedev.server.ci.job.trigger.JobTrigger;
import io.onedev.server.entitymanager.BuildManager;
import io.onedev.server.entitymanager.BuildQuerySettingManager;
import io.onedev.server.entitymanager.CodeCommentQuerySettingManager;
import io.onedev.server.entitymanager.CommitQuerySettingManager;
import io.onedev.server.entitymanager.IssueQuerySettingManager;
import io.onedev.server.entitymanager.ProjectManager;
import io.onedev.server.entitymanager.PullRequestQuerySettingManager;
import io.onedev.server.entitymanager.SettingManager;
import io.onedev.server.entitymanager.UserManager;
import io.onedev.server.event.RefUpdated;
import io.onedev.server.git.BlameBlock;
import io.onedev.server.git.Blob;
import io.onedev.server.git.BlobIdent;
import io.onedev.server.git.BlobIdentFilter;
import io.onedev.server.git.GitUtils;
import io.onedev.server.git.RefInfo;
import io.onedev.server.git.Submodule;
import io.onedev.server.git.command.BlameCommand;
import io.onedev.server.git.command.ListChangedFilesCommand;
import io.onedev.server.git.exception.NotFileException;
import io.onedev.server.git.exception.ObjectNotFoundException;
import io.onedev.server.model.Build.Status;
import io.onedev.server.model.support.BranchProtection;
import io.onedev.server.model.support.CommitMessageTransform;
import io.onedev.server.model.support.NamedBuildQuery;
import io.onedev.server.model.support.NamedCodeCommentQuery;
import io.onedev.server.model.support.NamedCommitQuery;
import io.onedev.server.model.support.Secret;
import io.onedev.server.model.support.TagProtection;
import io.onedev.server.model.support.WebHook;
import io.onedev.server.model.support.build.BuildSetting;
import io.onedev.server.model.support.issue.IssueSetting;
import io.onedev.server.model.support.pullrequest.NamedPullRequestQuery;
import io.onedev.server.persistence.SessionManager;
import io.onedev.server.persistence.TransactionManager;
import io.onedev.server.security.SecurityUtils;
import io.onedev.server.security.permission.DefaultPrivilege;
import io.onedev.server.storage.StorageManager;
import io.onedev.server.util.MatrixRunner;
import io.onedev.server.util.facade.ProjectFacade;
import io.onedev.server.util.jackson.DefaultView;
import io.onedev.server.util.patternset.PatternSet;
import io.onedev.server.util.usermatcher.UserMatcher;
import io.onedev.server.util.validation.Validatable;
import io.onedev.server.util.validation.annotation.ClassValidating;
import io.onedev.server.util.validation.annotation.ProjectName;
import io.onedev.server.web.editable.annotation.Editable;
import io.onedev.server.web.editable.annotation.Markdown;
import io.onedev.server.web.editable.annotation.NameOfEmptyValue;

@Entity
@Table(indexes={@Index(columnList="o_forkedFrom_id"), @Index(columnList="name")})
@Cache(usage=CacheConcurrencyStrategy.READ_WRITE)
@DynamicUpdate
@ClassValidating
@Editable
public class Project extends AbstractEntity implements Validatable {

	private static final long serialVersionUID = 1L;
	
	private static final int LAST_COMMITS_CACHE_THRESHOLD = 1000;
	
	public static final int MAX_UPLOAD_SIZE = 10; // In mega bytes
	
	@ManyToOne(fetch=FetchType.LAZY)
	@JoinColumn(nullable=true)
	private Project forkedFrom;

	@Column(nullable=false, unique=true)
	private String name;
	
	@Lob
	@Column(length=65535)
	private String description;
	
	private DefaultPrivilege defaultPrivilege;
	
	@Lob
	@Column(length=65535, name="COMMIT_MSG_TRANSFORM")
	@JsonView(DefaultView.class)
	private ArrayList<CommitMessageTransform> commitMessageTransforms = new ArrayList<>();
	
	/*
	 * Optimistic lock is necessary to ensure database integrity when update 
	 * branch and tag protection settings upon project renaming/deletion
	 */
	@Version
	private long version;
	
    @OneToMany(mappedBy="project")
    private Collection<Build> builds = new ArrayList<>();
    
	@Lob
	@Column(nullable=false, length=65535)
	@JsonView(DefaultView.class)
	private ArrayList<BranchProtection> branchProtections = new ArrayList<>();
	
	@Lob
	@Column(nullable=false, length=65535)
	@JsonView(DefaultView.class)
	private ArrayList<TagProtection> tagProtections = new ArrayList<>();
	
	@Lob
	@Column(nullable=false, length=65535)
	@JsonView(DefaultView.class)
	private ArrayList<Secret> secrets = new ArrayList<>();
	
	@Column(nullable=false)
	private Date createdAt = new Date();

	@OneToMany(mappedBy="targetProject", cascade=CascadeType.REMOVE)
	private Collection<PullRequest> incomingRequests = new ArrayList<>();
	
	@OneToMany(mappedBy="sourceProject")
	private Collection<PullRequest> outgoingRequests = new ArrayList<>();
	
	@OneToMany(mappedBy="project", cascade=CascadeType.REMOVE)
	private Collection<Issue> issues = new ArrayList<>();
	
    @OneToMany(mappedBy="forkedFrom")
	private Collection<Project> forks = new ArrayList<>();
    
	@OneToMany(mappedBy="project", cascade=CascadeType.REMOVE)
	private Collection<GroupAuthorization> groupAuthorizations = new ArrayList<>();
	
	@OneToMany(mappedBy="project", cascade=CascadeType.REMOVE)
	private Collection<UserAuthorization> userAuthorizations = new ArrayList<>();
	
	@OneToMany(mappedBy="project", cascade=CascadeType.REMOVE)
	private Collection<CodeComment> codeComments = new ArrayList<>();
	
	@OneToMany(mappedBy="project", cascade=CascadeType.REMOVE)
	private Collection<IssueQuerySetting> issueQuerySettings = new ArrayList<>();
	
	@OneToMany(mappedBy="project", cascade=CascadeType.REMOVE)
	private Collection<CommitQuerySetting> commitQuerySettings = new ArrayList<>();
	
	@OneToMany(mappedBy="project", cascade=CascadeType.REMOVE)
	private Collection<PullRequestQuerySetting> pullRequestQuerySettings = new ArrayList<>();
	
	@OneToMany(mappedBy="project", cascade=CascadeType.REMOVE)
	private Collection<CodeCommentQuerySetting> codeCommentQuerySettings = new ArrayList<>();
	
	@OneToMany(mappedBy="project", cascade=CascadeType.REMOVE)
	private Collection<BuildQuerySetting> buildQuerySettings = new ArrayList<>();
	
	@OneToMany(mappedBy="project", cascade=CascadeType.REMOVE)
	private Collection<Milestone> milestones = new ArrayList<>();
	
	@Lob
	@Column(length=65535, nullable=false)
	@JsonView(DefaultView.class)
	private IssueSetting issueSetting = new IssueSetting();
	
	@Lob
	@Column(length=65535, nullable=false)
	@JsonView(DefaultView.class)
	private BuildSetting buildSetting = new BuildSetting();
	
	@Lob
	@Column(length=65535, nullable=false)
	@JsonView(DefaultView.class)
	private ArrayList<NamedCommitQuery> savedCommitQueries = new ArrayList<>();
	{
		savedCommitQueries.add(new NamedCommitQuery("All", "all"));
		savedCommitQueries.add(new NamedCommitQuery("Default branch", "default-branch"));
		savedCommitQueries.add(new NamedCommitQuery("Authored by me", "authored-by-me"));
		savedCommitQueries.add(new NamedCommitQuery("Committed by me", "committed-by-me"));
		savedCommitQueries.add(new NamedCommitQuery("Committed recently", "after(last week)"));
	}
	
	@Lob
	@Column(length=65535, nullable=false)
	@JsonView(DefaultView.class)
	private ArrayList<NamedPullRequestQuery> savedPullRequestQueries = new ArrayList<>();
	{
		savedPullRequestQueries.add(new NamedPullRequestQuery("Open", "open"));
		savedPullRequestQueries.add(new NamedPullRequestQuery("To be reviewed by me", "to be reviewed by me"));
		savedPullRequestQueries.add(new NamedPullRequestQuery("To be changed by me", "submitted by me and someone requested for changes"));
		savedPullRequestQueries.add(new NamedPullRequestQuery("Request for changes by me", "requested for changes by me"));
		savedPullRequestQueries.add(new NamedPullRequestQuery("Approved by me", "approved by me"));
		savedPullRequestQueries.add(new NamedPullRequestQuery("Submitted by me", "submitted by me"));
		savedPullRequestQueries.add(new NamedPullRequestQuery("Submitted recently", "\"Submit Date\" is after \"last week\""));
		savedPullRequestQueries.add(new NamedPullRequestQuery("Updated recently", "\"Update Date\" is after \"last week\""));
		savedPullRequestQueries.add(new NamedPullRequestQuery("Closed", "merged or discarded"));
		savedPullRequestQueries.add(new NamedPullRequestQuery("All", "all"));
	}
	
	@Lob
	@Column(length=65535, nullable=false)
	@JsonView(DefaultView.class)
	private ArrayList<NamedCodeCommentQuery> savedCodeCommentQueries = new ArrayList<>(); 
	{
		savedCodeCommentQueries.add(new NamedCodeCommentQuery("All", "all"));
		savedCodeCommentQueries.add(new NamedCodeCommentQuery("Created by me", "created by me"));
		savedCodeCommentQueries.add(new NamedCodeCommentQuery("Created recently", "\"Create Date\" is after \"last week\""));
		savedCodeCommentQueries.add(new NamedCodeCommentQuery("Updated recently", "\"Update Date\" is after \"last week\""));
	}
	
	@Lob
	@Column(length=65535, nullable=false)
	@JsonView(DefaultView.class)
	private ArrayList<NamedBuildQuery> savedBuildQueries = new ArrayList<>();
	{
		savedBuildQueries.add(new NamedBuildQuery("All", "all"));
		savedBuildQueries.add(new NamedBuildQuery("Successful", "successful"));
		savedBuildQueries.add(new NamedBuildQuery("Failed", "failed"));
		savedBuildQueries.add(new NamedBuildQuery("In error", "in error"));
		savedBuildQueries.add(new NamedBuildQuery("Cancelled", "cancelled"));
		savedBuildQueries.add(new NamedBuildQuery("Timed out", "timed out"));
		savedBuildQueries.add(new NamedBuildQuery("Running", "running"));
		savedBuildQueries.add(new NamedBuildQuery("Waiting", "waiting"));
		savedBuildQueries.add(new NamedBuildQuery("Queueing", "queueing"));
		savedBuildQueries.add(new NamedBuildQuery("Build recently", "\"Submit Date\" is after \"last week\""));
	}
	
	@Lob
	@Column(length=65535, nullable=false)
	@JsonView(DefaultView.class)
	private ArrayList<WebHook> webHooks = new ArrayList<>();
	
	private transient Repository repository;
	
    private transient Map<BlobIdent, Optional<Blob>> blobCache;
    
    private transient Map<String, Optional<ObjectId>> objectIdCache;
    
    private transient Map<ObjectId, Optional<CISpec>> ciSpecCache;
    
    private transient Map<ObjectId, Map<String, Status>> commitStatusCache;
    
    private transient Map<ObjectId, Optional<RevCommit>> commitCache;
    
    private transient Map<String, Optional<Ref>> refCache;
    
    private transient Optional<String> defaultBranchOptional;
    
    private transient Optional<RevCommit> lastCommitOptional;
    
    private transient Optional<IssueQuerySetting> issueQuerySettingOfCurrentUserHolder;
    
    private transient Optional<PullRequestQuerySetting> pullRequestQuerySettingOfCurrentUserHolder;
    
    private transient Optional<CodeCommentQuerySetting> codeCommentQuerySettingOfCurrentUserHolder;
    
    private transient Optional<BuildQuerySetting> buildQuerySettingOfCurrentUserHolder;
    
    private transient Optional<CommitQuerySetting> commitQuerySettingOfCurrentUserHolder;
    
	private transient List<Milestone> sortedMilestones;
	
	private transient Map<String, Secret> secretMap;
	
	@Editable(order=100)
	@ProjectName
	@NotEmpty
	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	@Editable(order=200)
	@Markdown
	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	@Editable(order=300, description="Optionally specify default privilege for users not "
			+ "joining any teams of the project")
	@NameOfEmptyValue("No default privilege")
	@Nullable
	public DefaultPrivilege getDefaultPrivilege() {
		return defaultPrivilege;
	}

	public void setDefaultPrivilege(DefaultPrivilege defaultPrivilege) {
		this.defaultPrivilege = defaultPrivilege;
	}

	@Editable
	@Nullable
	@Valid
	public ArrayList<CommitMessageTransform> getCommitMessageTransforms() {
		return commitMessageTransforms;
	}

	public void setCommitMessageTransforms(ArrayList<CommitMessageTransform> commitMessageTransforms) {
		this.commitMessageTransforms = commitMessageTransforms;
	}

	public ArrayList<BranchProtection> getBranchProtections() {
		return branchProtections;
	}

	public void setBranchProtections(ArrayList<BranchProtection> branchProtections) {
		this.branchProtections = branchProtections;
	}

	public ArrayList<TagProtection> getTagProtections() {
		return tagProtections;
	}

	public void setTagProtections(ArrayList<TagProtection> tagProtections) {
		this.tagProtections = tagProtections;
	}

	public Date getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Date createdAt) {
		this.createdAt = createdAt;
	}

	public Collection<PullRequest> getIncomingRequests() {
		return incomingRequests;
	}

	public void setIncomingRequests(Collection<PullRequest> incomingRequests) {
		this.incomingRequests = incomingRequests;
	}

	public Collection<PullRequest> getOutgoingRequests() {
		return outgoingRequests;
	}

	public void setOutgoingRequests(Collection<PullRequest> outgoingRequests) {
		this.outgoingRequests = outgoingRequests;
	}

	public Collection<GroupAuthorization> getGroupAuthorizations() {
		return groupAuthorizations;
	}

	public void setGroupAuthorizations(Collection<GroupAuthorization> groupAuthorizations) {
		this.groupAuthorizations = groupAuthorizations;
	}

	public Collection<UserAuthorization> getUserAuthorizations() {
		return userAuthorizations;
	}

	public void setAuthorizedUsers(Collection<UserAuthorization> userAuthorizations) {
		this.userAuthorizations = userAuthorizations;
	}

	public Project getForkedFrom() {
		return forkedFrom;
	}

	public void setForkedFrom(Project forkedFrom) {
		this.forkedFrom = forkedFrom;
	}

	public Collection<Project> getForks() {
		return forks;
	}

	public void setForks(Collection<Project> forks) {
		this.forks = forks;
	}
	
	public List<RefInfo> getBranches() {
		List<RefInfo> refInfos = getRefInfos(Constants.R_HEADS);
		for (Iterator<RefInfo> it = refInfos.iterator(); it.hasNext();) {
			RefInfo refInfo = it.next();
			if (refInfo.getRef().getName().equals(GitUtils.branch2ref(getDefaultBranch()))) {
				it.remove();
				refInfos.add(0, refInfo);
				break;
			}
		}
		
		return refInfos;
    }
	
	public List<RefInfo> getTags() {
		return getRefInfos(Constants.R_TAGS);
    }
	
	public List<RefInfo> getRefInfos(String prefix) {
		try (RevWalk revWalk = new RevWalk(getRepository())) {
			List<Ref> refs = new ArrayList<Ref>(getRepository().getRefDatabase().getRefsByPrefix(prefix));
			List<RefInfo> refInfos = refs.stream()
					.map(ref->new RefInfo(revWalk, ref))
					.filter(refInfo->refInfo.getPeeledObj() instanceof RevCommit)
					.collect(Collectors.toList());
			Collections.sort(refInfos);
			Collections.reverse(refInfos);
			return refInfos;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
    }

	public Git git() {
		return Git.wrap(getRepository()); 
	}
	
	public File getGitDir() {
		return OneDev.getInstance(StorageManager.class).getProjectGitDir(getId());
	}
	
	/**
	 * Find fork root of this project. 
	 * 
	 * @return
	 * 			fork root of this project
	 */
	public Project getForkRoot() {
		if (forkedFrom != null) 
			return forkedFrom.getForkRoot();
		else 
			return this;
	}
	
	/**
	 * Get all descendant projects forking from current project.
	 * 
	 * @return
	 * 			all descendant projects forking from current project
	 */
	public List<Project> getForkDescendants() {
		List<Project> descendants = new ArrayList<>();
		if (getDefaultBranch() != null)
			descendants.add(this);
		for (Project fork: getForks()) { 
			descendants.addAll(fork.getForkDescendants());
		}
		
		return descendants;
	}
	
	public Repository getRepository() {
		if (repository == null) {
			repository = OneDev.getInstance(ProjectManager.class).getRepository(this);
		}
		return repository;
	}
	
	public String getUrl() {
		return OneDev.getInstance(SettingManager.class).getSystemSetting().getServerUrl() + "/projects/" + getName();
	}
	
	@Nullable
	public String getDefaultBranch() {
		if (defaultBranchOptional == null) {
			try {
				Ref headRef = getRepository().findRef("HEAD");
				if (headRef != null 
						&& headRef.isSymbolic() 
						&& headRef.getTarget().getName().startsWith(Constants.R_HEADS) 
						&& headRef.getObjectId() != null) {
					defaultBranchOptional = Optional.of(Repository.shortenRefName(headRef.getTarget().getName()));
				} else {
					defaultBranchOptional = Optional.absent();
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		return defaultBranchOptional.orNull();
	}
	
	public void setDefaultBranch(String defaultBranchName) {
		RefUpdate refUpdate = GitUtils.getRefUpdate(getRepository(), "HEAD");
		GitUtils.linkRef(refUpdate, GitUtils.branch2ref(defaultBranchName));
		defaultBranchOptional = null;
	}
	
	private Map<BlobIdent, Optional<Blob>> getBlobCache() {
		if (blobCache == null) {
			synchronized(this) {
				if (blobCache == null)
					blobCache = new ConcurrentHashMap<>();
			}
		}
		return blobCache;
	}
	
	/**
	 * Read blob content and cache result in repository in case the same blob 
	 * content is requested again. 
	 * 
	 * We made this method thread-safe as we are using ForkJoinPool to calculate 
	 * diffs of multiple blob changes concurrently, and this method will be 
	 * accessed concurrently in that special case.
	 * 
	 * @param blobIdent
	 * 			ident of the blob
	 * @return
	 * 			blob of specified blob ident
	 * @throws
	 * 			ObjectNotFoundException if blob of specified ident can not be found in repository 
	 * 			
	 */
	@Nullable
	public Blob getBlob(BlobIdent blobIdent, boolean mustExist) {
		Preconditions.checkArgument(blobIdent.revision!=null && blobIdent.path!=null && blobIdent.mode!=null, 
				"Revision, path and mode of ident param should be specified");
		
		Optional<Blob> blob = getBlobCache().get(blobIdent);
		if (blob == null) {
			try (RevWalk revWalk = new RevWalk(getRepository())) {
				ObjectId revId = getObjectId(blobIdent.revision, true);		
				RevTree revTree = revWalk.parseCommit(revId).getTree();
				TreeWalk treeWalk = TreeWalk.forPath(getRepository(), blobIdent.path, revTree);
				if (treeWalk != null) {
					ObjectId blobId = treeWalk.getObjectId(0);
					if (blobIdent.isGitLink()) {
						String url = getSubmodules(blobIdent.revision).get(blobIdent.path);
						if (url == null) {
							if (mustExist)
								throw new ObjectNotFoundException("Unable to find submodule '" + blobIdent.path + "' in .gitmodules");
							else
								blob = Optional.absent();
						} else {
							String hash = blobId.name();
							blob = Optional.of(new Blob(blobIdent, blobId, new Submodule(url, hash).toString().getBytes()));
						}
					} else if (blobIdent.isTree()) {
						throw new NotFileException("Path '" + blobIdent.path + "' is a tree");
					} else {
						blob = Optional.of(new Blob(blobIdent, blobId, treeWalk.getObjectReader()));
					}
				} else if (mustExist) {
					throw new ObjectNotFoundException("Unable to find blob path '" + blobIdent.path + "' in revision '" + blobIdent.revision + "'");
				} else {
					blob = Optional.absent();
				}
				getBlobCache().put(blobIdent, blob);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		return blob.orNull();
	}
	
	public InputStream getInputStream(BlobIdent ident) {
		try (RevWalk revWalk = new RevWalk(getRepository())) {
			ObjectId commitId = getObjectId(ident.revision, true);
			RevTree revTree = revWalk.parseCommit(commitId).getTree();
			TreeWalk treeWalk = TreeWalk.forPath(getRepository(), ident.path, revTree);
			if (treeWalk != null) {
				ObjectLoader objectLoader = treeWalk.getObjectReader().open(treeWalk.getObjectId(0));
				return objectLoader.openStream();
			} else {
				throw new ObjectNotFoundException("Unable to find blob path '" + ident.path + "' in revision '" + ident.revision + "'");
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	/**
	 * Get cached object id of specified revision.
	 * 
	 * @param revision
	 * 			revision to resolve object id for
	 * @param mustExist
	 * 			true to have the method throwing exception instead 
	 * 			of returning null if the revision does not exist
	 * @return
	 * 			object id of specified revision, or <tt>null</tt> if revision 
	 * 			does not exist and mustExist is specified as false
	 */
	@Nullable
	public ObjectId getObjectId(String revision, boolean mustExist) {
		if (objectIdCache == null)
			objectIdCache = new HashMap<>();
		
		Optional<ObjectId> optional = objectIdCache.get(revision);
		if (optional == null) {
			optional = Optional.fromNullable(GitUtils.resolve(getRepository(), revision));
			objectIdCache.put(revision, optional);
		}
		if (mustExist && !optional.isPresent())
			throw new ObjectNotFoundException("Unable to find object '" + revision + "'");
		return optional.orNull();
	}
	
	public void cacheObjectId(String revision, @Nullable ObjectId objectId) {
		if (objectIdCache == null)
			objectIdCache = new HashMap<>();
		
		objectIdCache.put(revision, Optional.fromNullable(objectId));
	}

	public Map<String, Status> getCommitStatus(ObjectId commitId) {
		Map<String, Status> commitStatus = getCommitStatusCache().get(commitId);
		if (commitStatus == null) {
			BuildManager buildManager = OneDev.getInstance(BuildManager.class);
			commitStatus = buildManager.queryStatus(this, Sets.newHashSet(commitId)).get(commitId);
			getCommitStatusCache().put(commitId, Preconditions.checkNotNull(commitStatus));
		}
		return commitStatus;
	}
	
	private Map<ObjectId, Map<String, Status>> getCommitStatusCache() {
		if (commitStatusCache == null)
			commitStatusCache = new HashMap<>();
		return commitStatusCache;
	}
	
	public void cacheCommitStatus(Map<ObjectId, Map<String, Status>> commitStatuses) {
		getCommitStatusCache().putAll(commitStatuses);
	}
	
	/**
	 * Get CI spec of specified commit
	 * @param commitId
	 * 			commit id to get CI spec for 
	 * @return
	 * 			CI spec of specified commit, or <tt>null</tt> if no CI spec is defined and 
	 * 			auto-detection also can not provide an appropriate CI spec  
	 * @throws
	 * 			Exception when CI spec is defined but not valid
	 */
	@Nullable
	public CISpec getCISpec(ObjectId commitId) {
		if (ciSpecCache == null)
			ciSpecCache = new HashMap<>();
		Optional<CISpec> ciSpecOpt = ciSpecCache.get(commitId);
		if (ciSpecOpt == null) {
			Blob blob = getBlob(new BlobIdent(commitId.name(), CISpec.BLOB_PATH, FileMode.TYPE_FILE), false);
			if (blob != null) {
				ciSpecOpt = Optional.fromNullable(CISpec.parse(blob.getBytes()));
			} else {				
				List<DefaultCISpecProvider> providers = new ArrayList<>(OneDev.getExtensions(DefaultCISpecProvider.class));
				providers.sort(Comparator.comparing(DefaultCISpecProvider::getPriority));
				CISpec ciSpec = null;
				for (DefaultCISpecProvider provider: providers) {
					ciSpec = provider.getDefaultCISpec(this, commitId);
					if (ciSpec != null) 
						break;
				}
				ciSpecOpt = Optional.fromNullable(ciSpec);
			}
			ciSpecCache.put(commitId, ciSpecOpt);
		}
		return ciSpecOpt.orNull();
	}
	
	public List<String> getJobNames() {
		CISpec ciSpec = getCISpec(getObjectId(getDefaultBranch(), true));
		if (ciSpec != null)
			return new ArrayList<>(ciSpec.getJobMap().keySet());
		else 
			return new ArrayList<>();
	}
	
	public LastCommitsOfChildren getLastCommitsOfChildren(String revision, @Nullable String path) {
		if (path == null)
			path = "";
		
		final File cacheDir = new File(
				OneDev.getInstance(StorageManager.class).getProjectInfoDir(getId()), 
				"last_commits/" + path + "/onedev_last_commits");
		
		final ReadWriteLock lock;
		try {
			lock = LockUtils.getReadWriteLock(cacheDir.getCanonicalPath());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		
		final Set<ObjectId> commitIds = new HashSet<>(); 
		
		lock.readLock().lock();
		try {
			if (cacheDir.exists()) {
				for (String each: cacheDir.list()) 
					commitIds.add(ObjectId.fromString(each));
			} 	
		} finally {
			lock.readLock().unlock();
		}
		
		org.eclipse.jgit.revwalk.LastCommitsOfChildren.Cache cache;
		if (!commitIds.isEmpty()) {
			cache = new org.eclipse.jgit.revwalk.LastCommitsOfChildren.Cache() {
	
				@SuppressWarnings("unchecked")
				@Override
				public Map<String, Value> getLastCommitsOfChildren(ObjectId commitId) {
					if (commitIds.contains(commitId)) {
						lock.readLock().lock();
						try {
							byte[] bytes = FileUtils.readFileToByteArray(new File(cacheDir, commitId.name()));
							return (Map<String, Value>) SerializationUtils.deserialize(bytes);
						} catch (IOException e) {
							throw new RuntimeException(e);
						} finally {
							lock.readLock().unlock();
						}
					} else {
						return null;
					}
				}
				
			};
		} else {
			cache = null;
		}

		final AnyObjectId commitId = getObjectId(revision, true);
		
		long time = System.currentTimeMillis();
		LastCommitsOfChildren lastCommits = new LastCommitsOfChildren(getRepository(), commitId, path, cache);
		long elapsed = System.currentTimeMillis()-time;
		if (elapsed > LAST_COMMITS_CACHE_THRESHOLD) {
			lock.writeLock().lock();
			try {
				if (!cacheDir.exists())
					FileUtils.createDir(cacheDir);
				FileUtils.writeByteArrayToFile(
						new File(cacheDir, commitId.name()), 
						SerializationUtils.serialize(lastCommits));
			} catch (IOException e) {
				throw new RuntimeException(e);
			} finally {
				lock.writeLock().unlock();
			}
		}
		return lastCommits;
	}

	@Nullable
	public Ref getRef(String revision) {
		if (refCache == null)
			refCache = new HashMap<>();
		Optional<Ref> optional = refCache.get(revision);
		if (optional == null) {
			try {
				optional = Optional.fromNullable(getRepository().findRef(revision));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			refCache.put(revision, optional);
		}
		return optional.orNull();
	}
	
	@Nullable
	public Ref getBranchRef(String revision) {
		Ref ref = getRef(revision);
		if (ref != null && ref.getName().startsWith(Constants.R_HEADS))
			return ref;
		else
			return null;
	}
	
	@Nullable
	public Ref getTagRef(String revision) {
		Ref ref = getRef(revision);
		if (ref != null && ref.getName().startsWith(Constants.R_TAGS))
			return ref;
		else
			return null;
	}
	
	@Nullable
	public RevCommit getRevCommit(String revision, boolean mustExist) {
		ObjectId revId = getObjectId(revision, mustExist);
		if (revId != null) {
			return getRevCommit(revId, mustExist);
		} else {
			return null;
		}
	}
	
	@Nullable
	public RevCommit getRevCommit(ObjectId revId, boolean mustExist) {
		if (commitCache == null)
			commitCache = new HashMap<>();
		RevCommit commit;
		Optional<RevCommit> optional = commitCache.get(revId);
		if (optional == null) {
			try (RevWalk revWalk = new RevWalk(getRepository())) {
				optional = Optional.fromNullable(GitUtils.parseCommit(revWalk, revId));
			}
			commitCache.put(revId, optional);
		}
		commit = optional.orNull();
		
		if (mustExist && commit == null)
			throw new ObjectNotFoundException("Unable to find commit associated with object id: " + revId);
		else
			return commit;
	}
	
	public List<Ref> getRefs(String prefix) {
		try {
			return getRepository().getRefDatabase().getRefsByPrefix(prefix);
		} catch (IOException e) {
			throw new RuntimeException(e);
		} 
	}
	
	public Map<String, String> getSubmodules(String revision) {
		Map<String, String> submodules = new HashMap<>();
		
		Blob blob = getBlob(new BlobIdent(revision, ".gitmodules", FileMode.REGULAR_FILE.getBits()), true);
		String content = new String(blob.getBytes());
		
		String path = null;
		String url = null;
		
		for (String line: StringUtils.splitAndTrim(content, "\r\n")) {
			if (line.startsWith("[") && line.endsWith("]")) {
				if (path != null && url != null)
					submodules.put(path, url);
				
				path = url = null;
			} else if (line.startsWith("path")) {
				path = StringUtils.substringAfter(line, "=").trim();
			} else if (line.startsWith("url")) {
				url = StringUtils.substringAfter(line, "=").trim();
			}
		}
		if (path != null && url != null)
			submodules.put(path, url);
		
		return submodules;
	}
    
    public void createBranch(String branchName, String branchRevision) {
		try {
			CreateBranchCommand command = git().branchCreate();
			command.setName(branchName);
			RevCommit commit = getRevCommit(branchRevision, true);
			command.setStartPoint(getRevCommit(branchRevision, true));
			command.call();
			String refName = GitUtils.branch2ref(branchName); 
			cacheObjectId(refName, commit);
			
	    	Subject subject = SecurityUtils.getSubject();
	    	ObjectId commitId = commit.copy();
	    	OneDev.getInstance(TransactionManager.class).runAfterCommit(new Runnable() {

				@Override
				public void run() {
			    	OneDev.getInstance(SessionManager.class).runAsync(new Runnable() {

						@Override
						public void run() {
							ThreadContext.bind(subject);
							try {
								Project project = OneDev.getInstance(ProjectManager.class).load(getId());
								OneDev.getInstance(ListenerRegistry.class).post(
										new RefUpdated(project, refName, ObjectId.zeroId(), commitId));
							} finally {
								ThreadContext.unbindSubject();
							}
						}
			    		
			    	});
				}
	    		
	    	});			
		} catch (GitAPIException e) {
			throw new RuntimeException(e);
		}
    }
    
    public void createTag(String tagName, String tagRevision, PersonIdent taggerIdent, @Nullable String tagMessage) {
		try {
			TagCommand tag = git().tag();
			tag.setName(tagName);
			if (tagMessage != null)
				tag.setMessage(tagMessage);
			tag.setTagger(taggerIdent);
			tag.setObjectId(getRevCommit(tagRevision, true));
			tag.call();
			
			String refName = GitUtils.tag2ref(tagName);
			cacheObjectId(refName, tag.getObjectId());
			
	    	Subject subject = SecurityUtils.getSubject();
	    	ObjectId commitId = tag.getObjectId().copy();
	    	OneDev.getInstance(TransactionManager.class).runAfterCommit(new Runnable() {

				@Override
				public void run() {
			    	OneDev.getInstance(SessionManager.class).runAsync(new Runnable() {

						@Override
						public void run() {
							ThreadContext.bind(subject);
							try {
								Project project = OneDev.getInstance(ProjectManager.class).load(getId());
								OneDev.getInstance(ListenerRegistry.class).post(
										new RefUpdated(project, refName, ObjectId.zeroId(), commitId));
							} finally {
								ThreadContext.unbindSubject();
							}
						}
			    		
			    	});
				}
	    		
	    	});			
		} catch (GitAPIException e) {
			throw new RuntimeException(e);
		}
    }
    
	public Collection<CodeComment> getCodeComments() {
		return codeComments;
	}

	public void setCodeComments(Collection<CodeComment> codeComments) {
		this.codeComments = codeComments;
	}

	public IssueSetting getIssueSetting() {
		return issueSetting;
	}

	public void setIssueSetting(IssueSetting issueSetting) {
		this.issueSetting = issueSetting;
	}

	public BuildSetting getBuildSetting() {
		return buildSetting;
	}

	public void setBuildSetting(BuildSetting buildSetting) {
		this.buildSetting = buildSetting;
	}

	public ArrayList<NamedCommitQuery> getSavedCommitQueries() {
		return savedCommitQueries;
	}

	public void setSavedCommitQueries(ArrayList<NamedCommitQuery> savedCommitQueries) {
		this.savedCommitQueries = savedCommitQueries;
	}
	
	public ArrayList<NamedPullRequestQuery> getSavedPullRequestQueries() {
		return savedPullRequestQueries;
	}

	public void setSavedPullRequestQueries(ArrayList<NamedPullRequestQuery> savedPullRequestQueries) {
		this.savedPullRequestQueries = savedPullRequestQueries;
	}
	
	@Nullable
	public NamedCommitQuery getSavedCommitQuery(String name) {
		for (NamedCommitQuery namedQuery: getSavedCommitQueries()) {
			if (namedQuery.getName().equals(name))
				return namedQuery;
		}
		return null;
	}
	
	@Nullable
	public NamedPullRequestQuery getSavedPullRequestQuery(String name) {
		for (NamedPullRequestQuery namedQuery: getSavedPullRequestQueries()) {
			if (namedQuery.getName().equals(name))
				return namedQuery;
		}
		return null;
	}
	
	@Nullable
	public NamedCodeCommentQuery getSavedCodeCommentQuery(String name) {
		for (NamedCodeCommentQuery namedQuery: getSavedCodeCommentQueries()) {
			if (namedQuery.getName().equals(name))
				return namedQuery;
		}
		return null;
	}
	
	@Nullable
	public NamedBuildQuery getSavedBuildQuery(String name) {
		for (NamedBuildQuery namedQuery: getSavedBuildQueries()) {
			if (namedQuery.getName().equals(name))
				return namedQuery;
		}
		return null;
	}
	
	public ArrayList<NamedCodeCommentQuery> getSavedCodeCommentQueries() {
		return savedCodeCommentQueries;
	}

	public void setSavedCodeCommentQueries(ArrayList<NamedCodeCommentQuery> savedCodeCommentQueries) {
		this.savedCodeCommentQueries = savedCodeCommentQueries;
	}
	
	public ArrayList<NamedBuildQuery> getSavedBuildQueries() {
		return savedBuildQueries;
	}

	public void setSavedBuildQueries(ArrayList<NamedBuildQuery> savedBuildQueries) {
		this.savedBuildQueries = savedBuildQueries;
	}

	public Collection<IssueQuerySetting> getIssueQuerySettings() {
		return issueQuerySettings;
	}

	public void setIssueQuerySettings(Collection<IssueQuerySetting> issueQuerySettings) {
		this.issueQuerySettings = issueQuerySettings;
	}

	public Collection<CommitQuerySetting> getCommitQuerySettings() {
		return commitQuerySettings;
	}

	public void setCommitQuerySettings(Collection<CommitQuerySetting> commitQuerySettings) {
		this.commitQuerySettings = commitQuerySettings;
	}

	public Collection<PullRequestQuerySetting> getPullRequestQuerySettings() {
		return pullRequestQuerySettings;
	}

	public void setPullRequestQuerySettings(Collection<PullRequestQuerySetting> pullRequestQuerySettings) {
		this.pullRequestQuerySettings = pullRequestQuerySettings;
	}

	public Collection<CodeCommentQuerySetting> getCodeCommentQuerySettings() {
		return codeCommentQuerySettings;
	}

	public void setCodeCommentQuerySettings(Collection<CodeCommentQuerySetting> codeCommentQuerySettings) {
		this.codeCommentQuerySettings = codeCommentQuerySettings;
	}
	
	public Collection<BuildQuerySetting> getBuildQuerySettings() {
		return buildQuerySettings;
	}

	public void setBuildQuerySettings(Collection<BuildQuerySetting> buildQuerySettings) {
		this.buildQuerySettings = buildQuerySettings;
	}

	public Collection<Build> getBuilds() {
		return builds;
	}

	public void setBuilds(Collection<Build> builds) {
		this.builds = builds;
	}

	public long getVersion() {
		return version;
	}

	public List<BlobIdent> getChildren(BlobIdent blobIdent, BlobIdentFilter blobIdentFilter) {
		return getChildren(blobIdent, blobIdentFilter, getObjectId(blobIdent.revision, true));
	}
	
	public List<BlobIdent> getChildren(BlobIdent blobIdent, BlobIdentFilter blobIdentFilter, ObjectId commitId) {
		Repository repository = getRepository();
		try (RevWalk revWalk = new RevWalk(repository)) {
			RevTree revTree = revWalk.parseCommit(commitId).getTree();
			
			TreeWalk treeWalk;
			if (blobIdent.path != null) {
				treeWalk = TreeWalk.forPath(repository, blobIdent.path, revTree);
				treeWalk.enterSubtree();
			} else {
				treeWalk = new TreeWalk(repository);
				treeWalk.addTree(revTree);
			}
			
			List<BlobIdent> children = new ArrayList<>();
			while (treeWalk.next()) { 
				BlobIdent child = new BlobIdent(blobIdent.revision, treeWalk.getPathString(), treeWalk.getRawMode(0)); 
				if (blobIdentFilter.filter(child))
					children.add(child);
			}
			Collections.sort(children);
			return children;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public int getMode(String revision, @Nullable String path) {
		if (path != null) {
			RevCommit commit = getRevCommit(revision, true);
			try {
				TreeWalk treeWalk = TreeWalk.forPath(getRepository(), path, commit.getTree());
				if (treeWalk != null) {
					return treeWalk.getRawMode(0);
				} else {
					throw new ObjectNotFoundException("Unable to find blob path '" + path
							+ "' in revision '" + revision + "'");
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		} else {
			return FileMode.TREE.getBits();
		}
	}

	public Collection<Milestone> getMilestones() {
		return milestones;
	}

	public void setMilestones(Collection<Milestone> milestones) {
		this.milestones = milestones;
	}

	public List<Milestone> getSortedMilestones() {
		if (sortedMilestones == null) {
			sortedMilestones = new ArrayList<>(getMilestones());
			Collections.sort(sortedMilestones, new Comparator<Milestone>() {

				@Override
				public int compare(Milestone o1, Milestone o2) {
					return o1.getDueDate().compareTo(o2.getDueDate());
				}
				
			});
		}
		return sortedMilestones;
	}
	
	public ArrayList<WebHook> getWebHooks() {
		return webHooks;
	}

	public void setWebHooks(ArrayList<WebHook> webHooks) {
		this.webHooks = webHooks;
	}

	@Editable
	public ArrayList<Secret> getSecrets() {
		return secrets;
	}

	public void setSecrets(ArrayList<Secret> secrets) {
		this.secrets = secrets;
	}
	
	public Map<String, Secret> getSecretMap() {
		if (secretMap == null) {
			secretMap = new HashMap<>();
			for (Secret secret: getSecrets())
				secretMap.put(secret.getName(), secret);
		}
		return secretMap;
	}

	@Nullable
	public TagProtection getTagProtection(String tagName, User user) {
		for (TagProtection protection: tagProtections) {
			if (protection.isEnabled() 
					&& UserMatcher.fromString(protection.getUser()).matches(this, user)
					&& PatternSet.fromString(protection.getTags()).matches(new ChildAwareMatcher(), tagName)) {
				return protection;
			}
		}
		return null;
	}
	
	@Nullable
	public BranchProtection getBranchProtection(String branchName, @Nullable User user) {
		for (BranchProtection protection: branchProtections) {
			if (protection.isEnabled() 
					&& UserMatcher.fromString(protection.getUser()).matches(this, user) 
					&& PatternSet.fromString(protection.getBranches()).matches(new ChildAwareMatcher(), branchName)) {
				return protection;
			}
		}
		return null;
	}

	public ProjectFacade getFacade() {
		return new ProjectFacade(this);
	}

	public RevCommit getLastCommit() {
		if (lastCommitOptional == null) {
			RevCommit lastCommit = null;
			try {
				for (Ref ref: getRepository().getRefDatabase().getRefsByPrefix(Constants.R_HEADS)) {
					RevCommit commit = getRevCommit(ref.getObjectId(), false);
					if (commit != null) {
						if (lastCommit != null) {
							if (commit.getCommitTime() > lastCommit.getCommitTime())
								lastCommit = commit;
						} else {
							lastCommit = commit;
						}
					}
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			lastCommitOptional = Optional.fromNullable(lastCommit);
		}
		return lastCommitOptional.orNull();
	}

	@Override
	public String toString() {
		return getName();
	}

	public List<User> getAuthors(String filePath, ObjectId commitId, @Nullable LinearRange range) {
		BlameCommand cmd = new BlameCommand(getGitDir());
		cmd.commitHash(commitId.name());
		cmd.file(filePath);
		cmd.range(range);

		List<User> authors = new ArrayList<>();
		UserManager userManager = OneDev.getInstance(UserManager.class);
		for (BlameBlock block: cmd.call()) {
			User author = userManager.find(block.getCommit().getAuthor());
			if (author != null && !authors.contains(author))
				authors.add(author);
		}
		
		return authors;
	}
	
	public IssueQuerySetting getIssueQuerySettingOfCurrentUser() {
		if (issueQuerySettingOfCurrentUserHolder == null) {
			User user = SecurityUtils.getUser();
			if (user != null) {
				IssueQuerySetting setting = OneDev.getInstance(IssueQuerySettingManager.class).find(this, user);
				if (setting == null) {
					setting = new IssueQuerySetting();
					setting.setProject(this);
					setting.setUser(user);
				}
				issueQuerySettingOfCurrentUserHolder = Optional.of(setting);
			} else {
				issueQuerySettingOfCurrentUserHolder = Optional.absent();
			}
		}
		return issueQuerySettingOfCurrentUserHolder.orNull();
	}
	
	public CommitQuerySetting getCommitQuerySettingOfCurrentUser() {
		if (commitQuerySettingOfCurrentUserHolder == null) {
			User user = SecurityUtils.getUser();
			if (user != null) {
				CommitQuerySetting setting = OneDev.getInstance(CommitQuerySettingManager.class).find(this, user);
				if (setting == null) {
					setting = new CommitQuerySetting();
					setting.setProject(this);
					setting.setUser(user);
				}
				commitQuerySettingOfCurrentUserHolder = Optional.of(setting);
			} else {
				commitQuerySettingOfCurrentUserHolder = Optional.absent();
			}
		}
		return commitQuerySettingOfCurrentUserHolder.orNull();
	}
	
	@Nullable
	public PullRequestQuerySetting getPullRequestQuerySettingOfCurrentUser() {
		if (pullRequestQuerySettingOfCurrentUserHolder == null) {
			User user = SecurityUtils.getUser();
			if (user != null) {
				PullRequestQuerySetting setting = OneDev.getInstance(PullRequestQuerySettingManager.class).find(this, user);
				if (setting == null) {
					setting = new PullRequestQuerySetting();
					setting.setProject(this);
					setting.setUser(user);
				}
				pullRequestQuerySettingOfCurrentUserHolder = Optional.of(setting);
			} else {
				pullRequestQuerySettingOfCurrentUserHolder = Optional.absent();
			}
		}
		return pullRequestQuerySettingOfCurrentUserHolder.orNull();
	}
	
	@Nullable
	public CodeCommentQuerySetting getCodeCommentQuerySettingOfCurrentUser() {
		if (codeCommentQuerySettingOfCurrentUserHolder == null) {
			User user = SecurityUtils.getUser();
			if (user != null) {
				CodeCommentQuerySetting setting = OneDev.getInstance(CodeCommentQuerySettingManager.class).find(this, user);
				if (setting == null) {
					setting = new CodeCommentQuerySetting();
					setting.setProject(this);
					setting.setUser(user);
				}
				codeCommentQuerySettingOfCurrentUserHolder = Optional.of(setting);
			} else {
				codeCommentQuerySettingOfCurrentUserHolder = Optional.absent();
			}
		}
		return codeCommentQuerySettingOfCurrentUserHolder.orNull();
	}
	
	@Nullable
	public BuildQuerySetting getBuildQuerySettingOfCurrentUser() {
		if (buildQuerySettingOfCurrentUserHolder == null) {
			User user = SecurityUtils.getUser();
			if (user != null) {
				BuildQuerySetting setting = OneDev.getInstance(BuildQuerySettingManager.class).find(this, user);
				if (setting == null) {
					setting = new BuildQuerySetting();
					setting.setProject(this);
					setting.setUser(user);
				}
				buildQuerySettingOfCurrentUserHolder = Optional.of(setting);
			} else {
				buildQuerySettingOfCurrentUserHolder = Optional.absent();
			}
		}
		return buildQuerySettingOfCurrentUserHolder.orNull();
	}
	
	@Nullable
	public Milestone getMilestone(@Nullable String milestoneName) {
		for (Milestone milestone: milestones) {
			if (milestone.getName().equals(milestoneName))
				return milestone;
		}
		return null;
	}

	public boolean isCommitOnBranches(ObjectId commitId, String branches) {
		CommitInfoManager commitInfoManager = OneDev.getInstance(CommitInfoManager.class);
		Collection<ObjectId> descendants = commitInfoManager.getDescendants(this, Sets.newHashSet(commitId));
		descendants.add(commitId);
	
		Matcher matcher = new ChildAwareMatcher();
		PatternSet branchPatterns = PatternSet.fromString(branches);
		for (RefInfo ref: getBranches()) {
			String branchName = Preconditions.checkNotNull(GitUtils.ref2branch(ref.getRef().getName()));
			if (descendants.contains(ref.getPeeledObj()) && branchPatterns.matches(matcher, branchName))
				return true;
		}
		return false;
	}

	public Collection<String> getChangedFiles(ObjectId oldObjectId, ObjectId newObjectId, 
			Map<String, String> gitEnvs) {
		if (gitEnvs != null && !gitEnvs.isEmpty()) {
			ListChangedFilesCommand cmd = new ListChangedFilesCommand(getGitDir(), gitEnvs);
			cmd.fromRev(oldObjectId.name()).toRev(newObjectId.name());
			return cmd.call();
		} else {
			return GitUtils.getChangedFiles(getRepository(), oldObjectId, newObjectId);
		}
	}
	
	public boolean isReviewRequiredForModification(User user, String branch, @Nullable String file) {
		BranchProtection branchProtection = getBranchProtection(branch, user);
		if (branchProtection != null) 
			return branchProtection.isReviewRequiredForModification(user, this, branch, file);
		else
			return false;
	}

	public boolean isReviewRequiredForPush(User user, String branch, ObjectId oldObjectId, 
			ObjectId newObjectId, Map<String, String> gitEnvs) {
		BranchProtection branchProtection = getBranchProtection(branch, user);
		if (branchProtection != null) { 		
			return branchProtection.isReviewRequiredForPush(user, this, branch, oldObjectId, newObjectId, gitEnvs);
		} else {
			return false;
		}
	}
	
	public boolean isBuildRequiredForModification(String branch, @Nullable String file) {
		// Exclude cispec from build requirement to avoid being locking out
		if (!CISpec.BLOB_PATH.equals(file)) {
			try {
				CISpec ciSpec = getCISpec(getObjectId(branch, true));
				if (ciSpec != null) {
					for (Job job: ciSpec.getJobs()) {
						for (JobTrigger trigger: job.getTriggers()) {
							if (trigger instanceof BranchUpdateTrigger) {
								BranchUpdateTrigger branchUpdateTrigger = (BranchUpdateTrigger) trigger;
								if (branchUpdateTrigger.isRejectIfNotSuccessful() 
										&& (branchUpdateTrigger.getBranches() == null || PatternSet.fromString(branchUpdateTrigger.getBranches()).matches(new ChildAwareMatcher(), branch))) {
									return true;
								}
							}
						}
					}
				}
			} catch (Exception e) {
			}
		}
		return false;
	}
	
	public boolean isBuildRequiredForPush(String branch, ObjectId oldObjectId, ObjectId newObjectId, 
			Map<String, String> gitEnvs) {
		// Exclude cispec from build requirement to avoid being locking out
		if (!getChangedFiles(oldObjectId, newObjectId, gitEnvs).contains(CISpec.BLOB_PATH)) {
			Collection<Build> builds = OneDev.getInstance(BuildManager.class).query(this, newObjectId);
			try {
				CISpec ciSpec = getCISpec(oldObjectId);
				if (ciSpec != null) {
					for (Job job: ciSpec.getJobs()) {
						for (JobTrigger trigger: job.getTriggers()) {
							if (trigger instanceof BranchUpdateTrigger) {
								BranchUpdateTrigger branchUpdateTrigger = (BranchUpdateTrigger) trigger;
								if (branchUpdateTrigger.isRejectIfNotSuccessful() 
										&& (branchUpdateTrigger.getBranches() == null || PatternSet.fromString(branchUpdateTrigger.getBranches()).matches(new ChildAwareMatcher(), branch))) {
									Map<String, List<List<String>>> paramMatrix = new HashMap<>();
									Set<String> secretParamNames = new HashSet<>();
									for (JobParam param: trigger.getParams()) { 
										paramMatrix.put(param.getName(), param.getValuesProvider().getValues());
										if (param.isSecret())
											secretParamNames.add(param.getName());
									}
									
									AtomicReference<Build> buildRef = new AtomicReference<>(null);
									new MatrixRunner<List<String>>(paramMatrix) {
										
										@Override
										public void run(Map<String, List<String>> params) {
											for (Build build: builds) {
												Map<String, List<String>> paramsWithoutSecrets = new HashMap<>(params);
												Map<String, List<String>> buildParamsWithoutSecrets = new HashMap<>(build.getParamMap());
												paramsWithoutSecrets.keySet().removeAll(secretParamNames);
												buildParamsWithoutSecrets.keySet().removeAll(secretParamNames);
												if (build.getJobName().equals(job.getName()) 
														&& buildParamsWithoutSecrets.equals(paramsWithoutSecrets)) {
													buildRef.set(build);
													break;
												}
											}
										}
										
									}.run();
									
									Build build = buildRef.get();
									if (build == null || build.getStatus() != Build.Status.SUCCESSFUL)
										return true;
								}
							}
						}
					}
				}
			} catch (Exception e) {
			}
		}
		return false;		
	}
	
	@Override
	public boolean isValid(ConstraintValidatorContext context) {
		boolean isValid = true;
		try {
			Secret.validateSecrets(getSecrets());
		} catch (ValidationException e) {
			isValid = false;
			context.buildConstraintViolationWithTemplate(e.getMessage()).addPropertyNode("secrets").addConstraintViolation();
		}

		if (!isValid)
			context.disableDefaultConstraintViolation();
		
		return isValid;
	}

}
