package io.onedev.server;

import java.io.Serializable;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;

import javax.inject.Singleton;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityNotFoundException;
import javax.persistence.OneToMany;
import javax.persistence.Transient;
import javax.persistence.Version;
import javax.validation.Configuration;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;

import org.apache.shiro.authc.credential.PasswordService;
import org.apache.shiro.authz.UnauthorizedException;
import org.apache.shiro.guice.aop.ShiroAopModule;
import org.apache.shiro.mgt.RememberMeManager;
import org.apache.shiro.realm.Realm;
import org.apache.shiro.web.filter.mgt.FilterChainManager;
import org.apache.shiro.web.filter.mgt.FilterChainResolver;
import org.apache.shiro.web.mgt.WebSecurityManager;
import org.apache.shiro.web.servlet.ShiroFilter;
import org.apache.wicket.Application;
import org.apache.wicket.core.request.mapper.StalePageException;
import org.apache.wicket.protocol.http.PageExpiredException;
import org.apache.wicket.protocol.http.WebApplication;
import org.apache.wicket.protocol.http.WicketFilter;
import org.apache.wicket.protocol.http.WicketServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;
import org.hibernate.CallbackException;
import org.hibernate.Interceptor;
import org.hibernate.ObjectNotFoundException;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.StaleStateException;
import org.hibernate.boot.model.naming.PhysicalNamingStrategy;
import org.hibernate.collection.internal.PersistentBag;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.type.Type;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.inject.matcher.AbstractMatcher;
import com.google.inject.matcher.Matchers;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.annotations.XStreamOmitField;
import com.thoughtworks.xstream.converters.basic.NullConverter;
import com.thoughtworks.xstream.converters.extended.ISO8601DateConverter;
import com.thoughtworks.xstream.converters.extended.ISO8601SqlTimestampConverter;
import com.thoughtworks.xstream.converters.reflection.ReflectionProvider;
import com.thoughtworks.xstream.core.JVM;
import com.thoughtworks.xstream.mapper.MapperWrapper;
import com.vladsch.flexmark.Extension;

import io.onedev.commons.launcher.bootstrap.Bootstrap;
import io.onedev.commons.launcher.loader.AbstractPlugin;
import io.onedev.commons.launcher.loader.AbstractPluginModule;
import io.onedev.commons.utils.ClassUtils;
import io.onedev.commons.utils.schedule.DefaultTaskScheduler;
import io.onedev.commons.utils.schedule.TaskScheduler;
import io.onedev.server.cache.BuildInfoManager;
import io.onedev.server.cache.CacheManager;
import io.onedev.server.cache.CodeCommentRelationInfoManager;
import io.onedev.server.cache.CommitInfoManager;
import io.onedev.server.cache.DefaultBuildInfoManager;
import io.onedev.server.cache.DefaultCacheManager;
import io.onedev.server.cache.DefaultCodeCommentRelationInfoManager;
import io.onedev.server.cache.DefaultCommitInfoManager;
import io.onedev.server.cache.DefaultUserInfoManager;
import io.onedev.server.cache.UserInfoManager;
import io.onedev.server.ci.DefaultCISpecProvider;
import io.onedev.server.ci.job.DefaultJobManager;
import io.onedev.server.ci.job.DependencyPopulator;
import io.onedev.server.ci.job.JobManager;
import io.onedev.server.ci.job.log.DefaultLogManager;
import io.onedev.server.ci.job.log.LogManager;
import io.onedev.server.ci.job.log.LogNormalizer;
import io.onedev.server.ci.job.log.instruction.LogInstruction;
import io.onedev.server.entitymanager.BuildDependenceManager;
import io.onedev.server.entitymanager.BuildManager;
import io.onedev.server.entitymanager.BuildParamManager;
import io.onedev.server.entitymanager.BuildQuerySettingManager;
import io.onedev.server.entitymanager.CodeCommentManager;
import io.onedev.server.entitymanager.CodeCommentQuerySettingManager;
import io.onedev.server.entitymanager.CodeCommentRelationManager;
import io.onedev.server.entitymanager.CodeCommentReplyManager;
import io.onedev.server.entitymanager.CommitQuerySettingManager;
import io.onedev.server.entitymanager.GroupAuthorizationManager;
import io.onedev.server.entitymanager.GroupManager;
import io.onedev.server.entitymanager.IssueChangeManager;
import io.onedev.server.entitymanager.IssueCommentManager;
import io.onedev.server.entitymanager.IssueFieldManager;
import io.onedev.server.entitymanager.IssueManager;
import io.onedev.server.entitymanager.IssueQuerySettingManager;
import io.onedev.server.entitymanager.IssueVoteManager;
import io.onedev.server.entitymanager.IssueWatchManager;
import io.onedev.server.entitymanager.MembershipManager;
import io.onedev.server.entitymanager.MilestoneManager;
import io.onedev.server.entitymanager.ProjectManager;
import io.onedev.server.entitymanager.PullRequestBuildManager;
import io.onedev.server.entitymanager.PullRequestChangeManager;
import io.onedev.server.entitymanager.PullRequestCommentManager;
import io.onedev.server.entitymanager.PullRequestManager;
import io.onedev.server.entitymanager.PullRequestQuerySettingManager;
import io.onedev.server.entitymanager.PullRequestReviewManager;
import io.onedev.server.entitymanager.PullRequestUpdateManager;
import io.onedev.server.entitymanager.PullRequestWatchManager;
import io.onedev.server.entitymanager.SettingManager;
import io.onedev.server.entitymanager.UrlManager;
import io.onedev.server.entitymanager.UserAuthorizationManager;
import io.onedev.server.entitymanager.UserManager;
import io.onedev.server.entitymanager.impl.DefaultBuildDependenceManager;
import io.onedev.server.entitymanager.impl.DefaultBuildManager;
import io.onedev.server.entitymanager.impl.DefaultBuildParamManager;
import io.onedev.server.entitymanager.impl.DefaultBuildQuerySettingManager;
import io.onedev.server.entitymanager.impl.DefaultCodeCommentManager;
import io.onedev.server.entitymanager.impl.DefaultCodeCommentQuerySettingManager;
import io.onedev.server.entitymanager.impl.DefaultCodeCommentRelationManager;
import io.onedev.server.entitymanager.impl.DefaultCodeCommentReplyManager;
import io.onedev.server.entitymanager.impl.DefaultCommitQuerySettingManager;
import io.onedev.server.entitymanager.impl.DefaultGroupAuthorizationManager;
import io.onedev.server.entitymanager.impl.DefaultGroupManager;
import io.onedev.server.entitymanager.impl.DefaultIssueChangeManager;
import io.onedev.server.entitymanager.impl.DefaultIssueCommentManager;
import io.onedev.server.entitymanager.impl.DefaultIssueFieldManager;
import io.onedev.server.entitymanager.impl.DefaultIssueManager;
import io.onedev.server.entitymanager.impl.DefaultIssueQuerySettingManager;
import io.onedev.server.entitymanager.impl.DefaultIssueVoteManager;
import io.onedev.server.entitymanager.impl.DefaultIssueWatchManager;
import io.onedev.server.entitymanager.impl.DefaultMembershipManager;
import io.onedev.server.entitymanager.impl.DefaultMilestoneManager;
import io.onedev.server.entitymanager.impl.DefaultProjectManager;
import io.onedev.server.entitymanager.impl.DefaultPullRequestBuildManager;
import io.onedev.server.entitymanager.impl.DefaultPullRequestChangeManager;
import io.onedev.server.entitymanager.impl.DefaultPullRequestCommentManager;
import io.onedev.server.entitymanager.impl.DefaultPullRequestManager;
import io.onedev.server.entitymanager.impl.DefaultPullRequestQuerySettingManager;
import io.onedev.server.entitymanager.impl.DefaultPullRequestReviewManager;
import io.onedev.server.entitymanager.impl.DefaultPullRequestUpdateManager;
import io.onedev.server.entitymanager.impl.DefaultPullRequestWatchManager;
import io.onedev.server.entitymanager.impl.DefaultSettingManager;
import io.onedev.server.entitymanager.impl.DefaultUserAuthorizationManager;
import io.onedev.server.entitymanager.impl.DefaultUserManager;
import io.onedev.server.git.GitFilter;
import io.onedev.server.git.GitPostReceiveCallback;
import io.onedev.server.git.GitPreReceiveCallback;
import io.onedev.server.git.config.GitConfig;
import io.onedev.server.maintenance.ApplyDatabaseConstraints;
import io.onedev.server.maintenance.BackupDatabase;
import io.onedev.server.maintenance.CheckDataVersion;
import io.onedev.server.maintenance.CleanDatabase;
import io.onedev.server.maintenance.DataManager;
import io.onedev.server.maintenance.DatabaseDialect;
import io.onedev.server.maintenance.DefaultDataManager;
import io.onedev.server.maintenance.ResetAdminPassword;
import io.onedev.server.maintenance.RestoreDatabase;
import io.onedev.server.maintenance.Upgrade;
import io.onedev.server.migration.JpaConverter;
import io.onedev.server.migration.PersistentBagConverter;
import io.onedev.server.model.support.authenticator.Authenticator;
import io.onedev.server.notification.CodeCommentNotificationManager;
import io.onedev.server.notification.CommitNotificationManager;
import io.onedev.server.notification.DefaultMailManager;
import io.onedev.server.notification.IssueNotificationManager;
import io.onedev.server.notification.MailManager;
import io.onedev.server.notification.PullRequestNotificationManager;
import io.onedev.server.notification.WebHookManager;
import io.onedev.server.persistence.DefaultIdManager;
import io.onedev.server.persistence.DefaultPersistManager;
import io.onedev.server.persistence.DefaultSessionManager;
import io.onedev.server.persistence.DefaultTransactionManager;
import io.onedev.server.persistence.HibernateInterceptor;
import io.onedev.server.persistence.IdManager;
import io.onedev.server.persistence.PersistListener;
import io.onedev.server.persistence.PersistManager;
import io.onedev.server.persistence.PrefixedNamingStrategy;
import io.onedev.server.persistence.SessionFactoryProvider;
import io.onedev.server.persistence.SessionInterceptor;
import io.onedev.server.persistence.SessionManager;
import io.onedev.server.persistence.SessionProvider;
import io.onedev.server.persistence.TransactionInterceptor;
import io.onedev.server.persistence.TransactionManager;
import io.onedev.server.persistence.annotation.Sessional;
import io.onedev.server.persistence.annotation.Transactional;
import io.onedev.server.persistence.dao.Dao;
import io.onedev.server.persistence.dao.DefaultDao;
import io.onedev.server.rest.RestConstants;
import io.onedev.server.rest.jersey.DefaultServletContainer;
import io.onedev.server.rest.jersey.JerseyConfigurator;
import io.onedev.server.rest.jersey.ResourceConfigProvider;
import io.onedev.server.search.code.DefaultIndexManager;
import io.onedev.server.search.code.DefaultSearchManager;
import io.onedev.server.search.code.IndexManager;
import io.onedev.server.search.code.SearchManager;
import io.onedev.server.security.BasicAuthenticationFilter;
import io.onedev.server.security.FilterChainConfigurator;
import io.onedev.server.security.OneAuthorizingRealm;
import io.onedev.server.security.OneFilterChainResolver;
import io.onedev.server.security.OnePasswordService;
import io.onedev.server.security.OneRememberMeManager;
import io.onedev.server.security.OneWebSecurityManager;
import io.onedev.server.storage.AttachmentStorageManager;
import io.onedev.server.storage.DefaultAttachmentStorageManager;
import io.onedev.server.storage.DefaultStorageManager;
import io.onedev.server.storage.StorageManager;
import io.onedev.server.util.jackson.ObjectMapperConfigurator;
import io.onedev.server.util.jackson.ObjectMapperProvider;
import io.onedev.server.util.jackson.git.GitObjectMapperConfigurator;
import io.onedev.server.util.jackson.hibernate.HibernateObjectMapperConfigurator;
import io.onedev.server.util.jetty.DefaultJettyRunner;
import io.onedev.server.util.jetty.JettyRunner;
import io.onedev.server.util.markdown.DefaultMarkdownManager;
import io.onedev.server.util.markdown.EntityReferenceManager;
import io.onedev.server.util.markdown.MarkdownManager;
import io.onedev.server.util.markdown.MarkdownProcessor;
import io.onedev.server.util.validation.DefaultEntityValidator;
import io.onedev.server.util.validation.EntityValidator;
import io.onedev.server.util.validation.ValidatorProvider;
import io.onedev.server.util.work.BatchWorkManager;
import io.onedev.server.util.work.DefaultBatchWorkManager;
import io.onedev.server.util.work.DefaultWorkExecutor;
import io.onedev.server.util.work.WorkExecutor;
import io.onedev.server.web.DefaultUrlManager;
import io.onedev.server.web.DefaultWicketFilter;
import io.onedev.server.web.DefaultWicketServlet;
import io.onedev.server.web.ExpectedExceptionContribution;
import io.onedev.server.web.OneWebApplication;
import io.onedev.server.web.ResourcePackScopeContribution;
import io.onedev.server.web.WebApplicationConfigurator;
import io.onedev.server.web.avatar.AvatarManager;
import io.onedev.server.web.avatar.DefaultAvatarManager;
import io.onedev.server.web.component.diff.DiffRenderer;
import io.onedev.server.web.component.markdown.SourcePositionTrackExtension;
import io.onedev.server.web.component.markdown.emoji.EmojiExtension;
import io.onedev.server.web.editable.DefaultEditSupportRegistry;
import io.onedev.server.web.editable.EditSupport;
import io.onedev.server.web.editable.EditSupportLocator;
import io.onedev.server.web.editable.EditSupportRegistry;
import io.onedev.server.web.mapper.OnePageMapper;
import io.onedev.server.web.page.layout.LayoutPage;
import io.onedev.server.web.page.layout.MainNavContribution;
import io.onedev.server.web.page.project.blob.render.BlobRendererContribution;
import io.onedev.server.web.page.test.TestPage;
import io.onedev.server.web.websocket.BuildEventBroadcaster;
import io.onedev.server.web.websocket.CodeCommentEventBroadcaster;
import io.onedev.server.web.websocket.CommitIndexedBroadcaster;
import io.onedev.server.web.websocket.DefaultWebSocketManager;
import io.onedev.server.web.websocket.IssueEventBroadcaster;
import io.onedev.server.web.websocket.PullRequestEventBroadcaster;
import io.onedev.server.web.websocket.WebSocketManager;
import io.onedev.server.web.websocket.WebSocketPolicyProvider;

/**
 * NOTE: Do not forget to rename moduleClass property defined in the pom if you've renamed this class.
 *
 */
public class CoreModule extends AbstractPluginModule {

	@Override
	protected void configure() {
		super.configure();
		
		bind(JettyRunner.class).to(DefaultJettyRunner.class);
		bind(ServletContextHandler.class).toProvider(DefaultJettyRunner.class);
		
		bind(ObjectMapper.class).toProvider(ObjectMapperProvider.class).in(Singleton.class);
		
		bind(ValidatorFactory.class).toProvider(new com.google.inject.Provider<ValidatorFactory>() {

			@Override
			public ValidatorFactory get() {
				Configuration<?> configuration = Validation.byDefaultProvider().configure();
				return configuration.buildValidatorFactory();
			}
			
		}).in(Singleton.class);
		
		bind(Validator.class).toProvider(ValidatorProvider.class).in(Singleton.class);

		// configure markdown
		bind(MarkdownManager.class).to(DefaultMarkdownManager.class);		
		
		configurePersistence();
		configureRestServices();
		configureWeb();
		
		bind(GitConfig.class).toProvider(GitConfigProvider.class);

		/*
		 * Declare bindings explicitly instead of using ImplementedBy annotation as
		 * HK2 to guice bridge can only search in explicit bindings in Guice   
		 */
		bind(StorageManager.class).to(DefaultStorageManager.class);
		bind(SettingManager.class).to(DefaultSettingManager.class);
		bind(DataManager.class).to(DefaultDataManager.class);
		bind(TaskScheduler.class).to(DefaultTaskScheduler.class).in(Singleton.class);
		bind(PullRequestCommentManager.class).to(DefaultPullRequestCommentManager.class);
		bind(CodeCommentManager.class).to(DefaultCodeCommentManager.class);
		bind(PullRequestManager.class).to(DefaultPullRequestManager.class);
		bind(PullRequestUpdateManager.class).to(DefaultPullRequestUpdateManager.class);
		bind(ProjectManager.class).to(DefaultProjectManager.class);
		bind(UserManager.class).to(DefaultUserManager.class);
		bind(PullRequestReviewManager.class).to(DefaultPullRequestReviewManager.class);
		bind(BuildManager.class).to(DefaultBuildManager.class);
		bind(BuildDependenceManager.class).to(DefaultBuildDependenceManager.class);
		bind(JobManager.class).to(DefaultJobManager.class);
		bind(LogManager.class).to(DefaultLogManager.class);
		bind(PullRequestBuildManager.class).to(DefaultPullRequestBuildManager.class);
		bind(MailManager.class).to(DefaultMailManager.class);
		bind(IssueManager.class).to(DefaultIssueManager.class);
		bind(IssueFieldManager.class).to(DefaultIssueFieldManager.class);
		bind(BuildParamManager.class).to(DefaultBuildParamManager.class);
		bind(PullRequestWatchManager.class).to(DefaultPullRequestWatchManager.class);
		bind(CommitInfoManager.class).to(DefaultCommitInfoManager.class);
		bind(UserInfoManager.class).to(DefaultUserInfoManager.class);
		bind(BatchWorkManager.class).to(DefaultBatchWorkManager.class);
		bind(GroupManager.class).to(DefaultGroupManager.class);
		bind(UserAuthorizationManager.class).to(DefaultUserAuthorizationManager.class);
		bind(GroupAuthorizationManager.class).to(DefaultGroupAuthorizationManager.class);
		bind(MembershipManager.class).to(DefaultMembershipManager.class);
		bind(PullRequestChangeManager.class).to(DefaultPullRequestChangeManager.class);
		bind(CodeCommentReplyManager.class).to(DefaultCodeCommentReplyManager.class);
		bind(AttachmentStorageManager.class).to(DefaultAttachmentStorageManager.class);
		bind(CodeCommentRelationInfoManager.class).to(DefaultCodeCommentRelationInfoManager.class);
		bind(BuildInfoManager.class).to(DefaultBuildInfoManager.class);
		bind(CodeCommentRelationManager.class).to(DefaultCodeCommentRelationManager.class);
		bind(WorkExecutor.class).to(DefaultWorkExecutor.class);
		bind(PullRequestNotificationManager.class);
		bind(CommitNotificationManager.class);
		bind(IssueNotificationManager.class);
		bind(EntityReferenceManager.class);
		bind(CodeCommentNotificationManager.class);
		bind(CodeCommentManager.class).to(DefaultCodeCommentManager.class);
		bind(IssueWatchManager.class).to(DefaultIssueWatchManager.class);
		bind(IssueChangeManager.class).to(DefaultIssueChangeManager.class);
		bind(IssueVoteManager.class).to(DefaultIssueVoteManager.class);
		bind(CacheManager.class).to(DefaultCacheManager.class);
		bind(MilestoneManager.class).to(DefaultMilestoneManager.class);
		bind(Session.class).toProvider(SessionProvider.class);
		bind(EntityManager.class).toProvider(SessionProvider.class);
		bind(SessionFactory.class).toProvider(SessionFactoryProvider.class);
		bind(EntityManagerFactory.class).toProvider(SessionFactoryProvider.class);
		bind(IssueCommentManager.class).to(DefaultIssueCommentManager.class);
		bind(IssueQuerySettingManager.class).to(DefaultIssueQuerySettingManager.class);
		bind(PullRequestQuerySettingManager.class).to(DefaultPullRequestQuerySettingManager.class);
		bind(CodeCommentQuerySettingManager.class).to(DefaultCodeCommentQuerySettingManager.class);
		bind(CommitQuerySettingManager.class).to(DefaultCommitQuerySettingManager.class);
		bind(BuildQuerySettingManager.class).to(DefaultBuildQuerySettingManager.class);
		bind(WebHookManager.class);

		contribute(ObjectMapperConfigurator.class, GitObjectMapperConfigurator.class);
	    contribute(ObjectMapperConfigurator.class, HibernateObjectMapperConfigurator.class);
	    contributeFromPackage(DependencyPopulator.class, DependencyPopulator.class);
	    
	    contribute(PersistListener.class, PullRequestNotificationManager.class);
	    
		bind(Realm.class).to(OneAuthorizingRealm.class);
		bind(RememberMeManager.class).to(OneRememberMeManager.class);
		bind(WebSecurityManager.class).to(OneWebSecurityManager.class);
		bind(FilterChainResolver.class).to(OneFilterChainResolver.class);
		bind(BasicAuthenticationFilter.class);
		bind(PasswordService.class).to(OnePasswordService.class);
		bind(ShiroFilter.class);
		install(new ShiroAopModule());
        contribute(FilterChainConfigurator.class, new FilterChainConfigurator() {

            @Override
            public void configure(FilterChainManager filterChainManager) {
                filterChainManager.createChain("/**/info/refs", "noSessionCreation, authcBasic");
                filterChainManager.createChain("/**/git-upload-pack", "noSessionCreation, authcBasic");
                filterChainManager.createChain("/**/git-receive-pack", "noSessionCreation, authcBasic");
            }
            
        });
        contributeFromPackage(Authenticator.class, Authenticator.class);
        
        contributeFromPackage(DefaultCISpecProvider.class, DefaultCISpecProvider.class);
        contributeFromPackage(LogNormalizer.class, LogNormalizer.class);
        
		bind(IndexManager.class).to(DefaultIndexManager.class);
		bind(SearchManager.class).to(DefaultSearchManager.class);
		
		bind(EntityValidator.class).to(DefaultEntityValidator.class);
		
		bind(GitFilter.class);
		bind(GitPreReceiveCallback.class);
		bind(GitPostReceiveCallback.class);
	}
	
	private void configureRestServices() {
		bind(ResourceConfig.class).toProvider(ResourceConfigProvider.class).in(Singleton.class);
		bind(ServletContainer.class).to(DefaultServletContainer.class);
		
		contribute(FilterChainConfigurator.class, new FilterChainConfigurator() {

			@Override
			public void configure(FilterChainManager filterChainManager) {
				filterChainManager.createChain("/rest/**", "noSessionCreation, authcBasic");
			}
			
		});
		
		contribute(JerseyConfigurator.class, new JerseyConfigurator() {
			
			@Override
			public void configure(ResourceConfig resourceConfig) {
				resourceConfig.packages(RestConstants.class.getPackage().getName());
			}
			
		});
	}

	private void configureWeb() {
		bind(WicketServlet.class).to(DefaultWicketServlet.class);
		bind(WicketFilter.class).to(DefaultWicketFilter.class);
		bind(WebSocketPolicy.class).toProvider(WebSocketPolicyProvider.class);
		bind(EditSupportRegistry.class).to(DefaultEditSupportRegistry.class);
		bind(WebSocketManager.class).to(DefaultWebSocketManager.class);
		
		contributeFromPackage(EditSupport.class, EditSupport.class);
		
		bind(WebApplication.class).to(OneWebApplication.class);
		bind(Application.class).to(OneWebApplication.class);
		bind(AvatarManager.class).to(DefaultAvatarManager.class);
		bind(WebSocketManager.class).to(DefaultWebSocketManager.class);
		
		contributeFromPackage(EditSupport.class, EditSupportLocator.class);
		
		contribute(WebApplicationConfigurator.class, new WebApplicationConfigurator() {
			
			@Override
			public void configure(WebApplication application) {
				application.mount(new OnePageMapper("/test", TestPage.class));
			}
			
		});
		
		bind(CommitIndexedBroadcaster.class);
		
		contributeFromPackage(DiffRenderer.class, DiffRenderer.class);
		contributeFromPackage(BlobRendererContribution.class, BlobRendererContribution.class);

		contribute(Extension.class, new EmojiExtension());
		contribute(Extension.class, new SourcePositionTrackExtension());
		
		contributeFromPackage(MarkdownProcessor.class, MarkdownProcessor.class);

		contribute(ResourcePackScopeContribution.class, new ResourcePackScopeContribution() {
			
			@Override
			public Collection<Class<?>> getResourcePackScopes() {
				return Lists.newArrayList(OneWebApplication.class);
			}
			
		});
		contribute(ExpectedExceptionContribution.class, new ExpectedExceptionContribution() {
			
			@SuppressWarnings("unchecked")
			@Override
			public Collection<Class<? extends Exception>> getExpectedExceptionClasses() {
				return Sets.newHashSet(ConstraintViolationException.class, EntityNotFoundException.class, 
						ObjectNotFoundException.class, StaleStateException.class, UnauthorizedException.class, 
						OneException.class, PageExpiredException.class, StalePageException.class);
			}
			
		});

		bind(UrlManager.class).to(DefaultUrlManager.class);
		bind(CodeCommentEventBroadcaster.class);
		bind(PullRequestEventBroadcaster.class);
		bind(IssueEventBroadcaster.class);
		bind(BuildEventBroadcaster.class);
		
		contribute(MainNavContribution.class, new MainNavContribution() {
			
			@Override
			public boolean isAuthorized() {
				return false;
			}
			
			@Override
			public Class<? extends LayoutPage> getPageClass() {
				throw new UnsupportedOperationException();
			}
			
			@Override
			public int getOrder() {
				throw new UnsupportedOperationException();
			}
			
			@Override
			public String getLabel() {
				throw new UnsupportedOperationException();
			}

			@Override
			public boolean isActive(LayoutPage page) {
				return false;
			}
			
		});
	}
	
	private void configurePersistence() {
		// Use an optional binding here in case our client does not like to 
		// start persist service provided by this plugin
		bind(Interceptor.class).to(HibernateInterceptor.class);
		bind(PhysicalNamingStrategy.class).toInstance(new PrefixedNamingStrategy("o_"));
		
		bind(SessionManager.class).to(DefaultSessionManager.class);
		bind(TransactionManager.class).to(DefaultTransactionManager.class);
		bind(IdManager.class).to(DefaultIdManager.class);
		bind(Dao.class).to(DefaultDao.class);
		
	    TransactionInterceptor transactionInterceptor = new TransactionInterceptor();
	    requestInjection(transactionInterceptor);
	    
	    bindInterceptor(Matchers.any(), new AbstractMatcher<AnnotatedElement>() {

			@Override
			public boolean matches(AnnotatedElement element) {
				return element.isAnnotationPresent(Transactional.class) && !((Method) element).isSynthetic();
			}
	    	
	    }, transactionInterceptor);
	    
	    SessionInterceptor sessionInterceptor = new SessionInterceptor();
	    requestInjection(sessionInterceptor);
	    
	    bindInterceptor(Matchers.any(), new AbstractMatcher<AnnotatedElement>() {

			@Override
			public boolean matches(AnnotatedElement element) {
				return element.isAnnotationPresent(Sessional.class) && !((Method) element).isSynthetic();
			}
	    	
	    }, sessionInterceptor);
	    
	    contributeFromPackage(LogInstruction.class, LogInstruction.class);
	    
	    contribute(PersistListener.class, new PersistListener() {
			
			@Override
			public boolean onSave(Object entity, Serializable id, Object[] state, String[] propertyNames, Type[] types)
					throws CallbackException {
				return false;
			}
			
			@Override
			public boolean onLoad(Object entity, Serializable id, Object[] state, String[] propertyNames, Type[] types)
					throws CallbackException {
				return false;
			}
			
			@Override
			public boolean onFlushDirty(Object entity, Serializable id, Object[] currentState, Object[] previousState,
					String[] propertyNames, Type[] types) throws CallbackException {
				return false;
			}
			
			@Override
			public void onDelete(Object entity, Serializable id, Object[] state, String[] propertyNames, Type[] types)
					throws CallbackException {
			}

		});
	    
		bind(XStream.class).toProvider(new com.google.inject.Provider<XStream>() {

			@SuppressWarnings("rawtypes")
			@Override
			public XStream get() {
				ReflectionProvider reflectionProvider = JVM.newReflectionProvider();
				XStream xstream = new XStream(reflectionProvider) {

					@Override
					protected MapperWrapper wrapMapper(MapperWrapper next) {
						return new MapperWrapper(next) {
							
							@Override
							public boolean shouldSerializeMember(Class definedIn, String fieldName) {
								Field field = reflectionProvider.getField(definedIn, fieldName);
								
								return field.getAnnotation(XStreamOmitField.class) == null && 
										field.getAnnotation(Transient.class) == null && 
										field.getAnnotation(OneToMany.class) == null &&
										field.getAnnotation(Version.class) == null;
							}
							
							@SuppressWarnings("unchecked")
							@Override
							public String serializedClass(Class type) {
								if (type == PersistentBag.class)
									return super.serializedClass(ArrayList.class);
								else if (type != null)
									return super.serializedClass(ClassUtils.unproxy(type));
								else
									return super.serializedClass(type);
							}
							
						};
					}
					
				};
				
				// register NullConverter as highest; otherwise NPE when unmarshal a map 
				// containing an entry with value set to null.
				xstream.registerConverter(new NullConverter(), XStream.PRIORITY_VERY_HIGH);
				xstream.registerConverter(new PersistentBagConverter(xstream.getMapper()), 200);
				xstream.registerConverter(new JpaConverter(xstream.getMapper(), xstream.getReflectionProvider()));
				xstream.registerConverter(new ISO8601DateConverter(), 100);
				xstream.registerConverter(new ISO8601SqlTimestampConverter(), 100); 
				xstream.autodetectAnnotations(true);
				
				return xstream;
			}
			
		}).in(Singleton.class);
		
		if (Bootstrap.command != null) {
			if (RestoreDatabase.COMMAND.equals(Bootstrap.command.getName()))
				bind(PersistManager.class).to(RestoreDatabase.class);
			else if (ApplyDatabaseConstraints.COMMAND.equals(Bootstrap.command.getName()))
				bind(PersistManager.class).to(ApplyDatabaseConstraints.class);
			else if (BackupDatabase.COMMAND.equals(Bootstrap.command.getName()))
				bind(PersistManager.class).to(BackupDatabase.class);
			else if (CheckDataVersion.COMMAND.equals(Bootstrap.command.getName()))
				bind(PersistManager.class).to(CheckDataVersion.class);
			else if (Upgrade.COMMAND.equals(Bootstrap.command.getName()))
				bind(PersistManager.class).to(Upgrade.class);
			else if (CleanDatabase.COMMAND.equals(Bootstrap.command.getName()))
				bind(PersistManager.class).to(CleanDatabase.class);
			else if (DatabaseDialect.COMMAND.equals(Bootstrap.command.getName()))
				bind(PersistManager.class).to(DatabaseDialect.class);
			else if (ResetAdminPassword.COMMAND.equals(Bootstrap.command.getName()))
				bind(PersistManager.class).to(ResetAdminPassword.class);
			else	
				throw new RuntimeException("Unrecognized command: " + Bootstrap.command.getName());
		} else {
			bind(PersistManager.class).to(DefaultPersistManager.class);
		}		
	}
	
	@Override
	protected Class<? extends AbstractPlugin> getPluginClass() {
		return OneDev.class;
	}

}
