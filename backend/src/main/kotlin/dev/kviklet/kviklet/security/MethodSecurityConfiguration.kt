package dev.kviklet.kviklet.security

import dev.kviklet.kviklet.service.IdResolver
import org.aopalliance.aop.Advice
import org.aopalliance.intercept.MethodInterceptor
import org.aopalliance.intercept.MethodInvocation
import org.springframework.aop.Advisor
import org.springframework.aop.Pointcut
import org.springframework.aop.PointcutAdvisor
import org.springframework.aop.framework.AopInfrastructureBean
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut
import org.springframework.beans.factory.config.BeanDefinition.ROLE_INFRASTRUCTURE
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Role
import org.springframework.core.Ordered
import org.springframework.core.annotation.AnnotationUtils
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import java.util.function.Supplier

enum class Resource(val resourceName: String) {
    DATASOURCE("datasource"),
    DATASOURCE_CONNECTION("datasource_connection"),
    EXECUTION_REQUEST("execution_request"),
    EVENT("event"),
    ROLE("role"),
}

enum class Permission(
    val resource: Resource,
    // if action is null, only the parent requiredPermission is checked
    val action: String?,
    val requiredPermission: Permission?,
) {
    DATASOURCE_GET(Resource.DATASOURCE, "get", null),
    DATASOURCE_EDIT(Resource.DATASOURCE, "edit", DATASOURCE_GET),
    DATASOURCE_CREATE(Resource.DATASOURCE, "create", DATASOURCE_GET),

    DATASOURCE_CONNECTION_GET(Resource.DATASOURCE_CONNECTION, "get", DATASOURCE_GET),
    DATASOURCE_CONNECTION_EDIT(Resource.DATASOURCE_CONNECTION, "edit", DATASOURCE_CONNECTION_GET),
    DATASOURCE_CONNECTION_CREATE(Resource.DATASOURCE_CONNECTION, "create", DATASOURCE_CONNECTION_GET),

    EXECUTION_REQUEST_GET(Resource.EXECUTION_REQUEST, "get", DATASOURCE_CONNECTION_GET),
    EXECUTION_REQUEST_EDIT(Resource.EXECUTION_REQUEST, "edit", EXECUTION_REQUEST_GET),
    EXECUTION_REQUEST_EXECUTE(Resource.EXECUTION_REQUEST, "execute", EXECUTION_REQUEST_GET),

    ROLE_GET(Resource.ROLE, "get", null),
    ROLE_EDIT(Resource.ROLE, "edit", ROLE_GET), ;

    fun getPermissionString(): String {
        return "${this.resource.resourceName}:${this.action}"
    }
}

interface SecuredDomainId {
    override fun toString(): String
}

interface SecuredDomainObject {
    fun getId(): String?
    fun getDomainObjectType(): Resource
    fun getRelated(resource: Resource): SecuredDomainObject?
    fun auth(permission: Permission, userDetails: UserDetailsWithId): Boolean = true
}

@Target(AnnotationTarget.FUNCTION)
@Retention
annotation class Policy(val permission: Permission)

@Configuration
@EnableMethodSecurity(prePostEnabled = true)
class MethodSecurityConfig(
    private val idResolver: IdResolver,
) {

    @Bean
    @Role(ROLE_INFRASTRUCTURE)
    fun authorizationManagerBeforeMethodInterception(manager: MyAuthorizationManager): Advisor {
        return AuthorizationManagerInterceptor(
            AnnotationMatchingPointcut(null, Policy::class.java, true),
            manager,
            idResolver,
        )
    }
}

@Component
class MyAuthorizationManager {
    fun check(
        authentication: Supplier<Authentication>,
        invocation: MethodInvocation,
        returnObject: SecuredDomainObject? = null,
    ): AuthorizationDecision {
        val policyAnnotation: Policy = AnnotationUtils.findAnnotation(invocation.method, Policy::class.java)!!
        val policies = authentication.get().authorities.filterIsInstance<PolicyGrantedAuthority>()

        var p: Permission = policyAnnotation.permission

        do {
            if (!policies.vote(p, returnObject?.getRelated(p.resource)).isAllowed()) {
                return AuthorizationDecision(false)
            }
            if (returnObject?.auth(p, authentication.get().principal as UserDetailsWithId) == false) {
                return AuthorizationDecision(false)
            }
        } while ((p.requiredPermission != null).also { if (it) p = p.requiredPermission!! })
        return AuthorizationDecision(true)
    }
}

class AuthorizationManagerInterceptor(
    private val pointcut: Pointcut,
    private val authorizationManager: MyAuthorizationManager,
    private val idResolver: IdResolver,
) : Ordered, MethodInterceptor, PointcutAdvisor, AopInfrastructureBean {

    private val authentication: Supplier<Authentication> = Supplier {
        SecurityContextHolder.getContextHolderStrategy().context.authentication
            ?: throw AuthenticationCredentialsNotFoundException(
                "An Authentication object was not found in the SecurityContext",
            )
    }

    override fun getOrder(): Int = 500

    override fun getAdvice(): Advice = this

    override fun getPointcut(): Pointcut = this.pointcut

    override fun invoke(invocation: MethodInvocation): Any? {
        attemptPreAuthorization(invocation)
        val returnedObject = invocation.proceed()
        return attemptPostAuthorization(invocation, returnedObject)
    }

    private fun attemptPostAuthorization(invocation: MethodInvocation, returnedObject: Any?): Any? {
        return if (returnedObject is SecuredDomainObject) {
            if (!authorizationManager.check(authentication, invocation, returnedObject).isGranted) {
                throw AccessDeniedException("Access Denied")
            }
            returnedObject
        } else if (returnedObject is Collection<*>) {
            filterCollection(invocation, returnedObject as MutableCollection<*>)
        } else if (returnedObject == null) {
            null
        } else {
            throw IllegalStateException("Expected SecuredDomainObject, got $returnedObject.")
        }
    }

    private fun <T> filterCollection(
        invocation: MethodInvocation,
        filterTarget: MutableCollection<T>,
    ): MutableCollection<T> {
        val retain: MutableList<T> = ArrayList(filterTarget.size)
        for (filterObject in filterTarget) {
            if (filterObject is SecuredDomainObject) {
                if (authorizationManager.check(authentication, invocation, filterObject).isGranted) {
                    retain.add(filterObject)
                }
            } else {
                throw IllegalStateException("Expected SecuredDomainObject, got $filterObject.")
            }
        }
        filterTarget.clear()
        filterTarget.addAll(retain)
        return retain
    }

    private fun attemptPreAuthorization(mi: MethodInvocation) {
        val domainIds: List<SecuredDomainId> = mi.arguments.filterIsInstance<SecuredDomainId>()

        if (domainIds.isEmpty()) {
            if (!authorizationManager.check(authentication, mi).isGranted) {
                throw AccessDeniedException("Access Denied")
            }
        } else if (domainIds.size == 1) {
            if (!authorizationManager.check(authentication, mi, idResolver.resolve(domainIds[0])).isGranted) {
                throw AccessDeniedException("Access Denied")
            }
        } else {
            throw IllegalStateException("Only one SecuredDomainId is allowed per method.")
        }

        //        this.eventPublisher.publishAuthorizationEvent<MethodInvocation>(this.authentication, mi, decision)
    }
}
