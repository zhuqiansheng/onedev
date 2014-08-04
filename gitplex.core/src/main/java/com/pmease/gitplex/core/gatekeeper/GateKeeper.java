package com.pmease.gitplex.core.gatekeeper;

import java.io.Serializable;

import com.pmease.commons.editable.annotation.Editable;
import com.pmease.commons.util.trimmable.Trimmable;
import com.pmease.gitplex.core.gatekeeper.checkresult.CheckResult;
import com.pmease.gitplex.core.model.Branch;
import com.pmease.gitplex.core.model.PullRequest;
import com.pmease.gitplex.core.model.User;
import com.pmease.gitplex.core.model.Repository;

@Editable(name="Misc")
public interface GateKeeper extends Trimmable, Serializable {
	
	/**
	 * Check the gate keeper against specified request. This is typically used to determine 
	 * whether or not to accept a pull request. 
	 * 
	 * @param request
	 *			pull request to be checked
	 * @return
	 * 			result of the check
	 */
	CheckResult checkRequest(PullRequest request);

	/**
	 * Check if specified user can modify specified file in specified branch.
	 *
	 * @param user
	 * 			user to be checked
	 * @param branch
	 * 			branch to be checked
	 * @param file
	 * 			file to be checked
	 * @return
	 * 			result of the check. 
	 */
	CheckResult checkFile(User user, Branch branch, String file);
	
	/**
	 * Check if specified user can modify/delete specified branch.
	 *
	 * @param user
	 * 			user to be checked
	 * @param branch
	 * 			branch to be checked
	 * @return
	 * 			result of the check. 
	 */
	CheckResult checkBranch(User user, Branch branch);

	/**
	 * Check if specified user can push specified commit to specified branch.
	 *
	 * @param user
	 * 			user to be checked
	 * @param branch
	 * 			branch to be checked
	 * @param commit
	 * 			commit to be checked
	 * @return
	 * 			result of the check
	 */
	CheckResult checkCommit(User user, Branch branch, String commit);
	
	/**
	 * Check if specified user can push specified reference to specified repository. 
	 * 
	 * @param user
	 * 			user to be checked
	 * @param repository
	 * 			repository to be checked
	 * @param refName
	 * 			reference name to be checked
	 * @return
	 * 			result of the check
	 */
	CheckResult checkRef(User user, Repository repository, String refName);
	
	boolean isEnabled();
}