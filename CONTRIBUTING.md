# Contributing to RapidWright

RapidWright welcomes contributions from the community.  You can contribute to RapidWright in the following ways:
1. Report bugs or requesting new features by filing [a new Issue on GitHub](https://github.com/Xilinx/RapidWright/issues/new)
2. You can send patches or new feature implementations to RapidWright by creating a pull request
3. You can engage in discussion and ideas on the [RapidWright forum](https://groups.google.com/g/rapidwright)

## Reporting Issues
If you are reporting a bug, please include instructions on how to reproduce the undesired behavior.  If possible/relevant, please include a design checkpoint (DCP) file, Java code and/or Python script that can reproduce the problem.

## Contributing Code 
Please use the GitHub Pull Request (PR) mechanism for making code contributions.  In order for RapidWright to accept your code, please sign your work as described below.  Please ensure that your code is compatible with the [Apache 2.0 license](https://www.apache.org/licenses/LICENSE-2.0) as this license governs all of the open source code in the RapidWright repository.  After the pull request is made, we will review it and may provide feedback to you.  When it is accepted, it will be merged into the repository.

### Sign Your Work
Please use the *Signed-off-by* line at the end of your patch which indicates that you accept the Developer Certificate of Origin (DCO) defined by https://developercertificate.org/ reproduced below:

```
  Developer Certificate of Origin
  Version 1.1

  Copyright (C) 2004, 2006 The Linux Foundation and its contributors.
  1 Letterman Drive
  Suite D4700
  San Francisco, CA, 94129

  Everyone is permitted to copy and distribute verbatim copies of this
  license document, but changing it is not allowed.


  Developer's Certificate of Origin 1.1

  By making a contribution to this project, I certify that:

  (a) The contribution was created in whole or in part by me and I
      have the right to submit it under the open source license
      indicated in the file; or

  (b) The contribution is based upon previous work that, to the best
      of my knowledge, is covered under an appropriate open source
      license and I have the right under that license to submit that
      work with modifications, whether created in whole or in part
      by me, under the same open source license (unless I am
      permitted to submit under a different license), as indicated
      in the file; or

  (c) The contribution was provided directly to me by some other
      person who certified (a), (b) or (c) and I have not modified
      it.

  (d) I understand and agree that this project and the contribution
      are public and that a record of the contribution (including all
      personal information I submit with it, including my sign-off) is
      maintained indefinitely and may be redistributed consistent with
      this project or the open source license(s) involved.
```

Here is an example Signed-off-by line which indicates that the contributor accepts DCO::

```
  This is my commit message

  Signed-off-by: Jane Doe <jane.doe@example.com>
```
You can add the `Signed-off-by` message manually at the end of your commit message.  However, that can be tedious so git has a useful command `-s | --signoff` that can automatically add the sign off message to your commit when submitting.  In order to set this up, you will need to update your git settings:

Adding your first and last name:
```
git config user.name "FIRST_NAME LAST_NAME"
```
Adding your email:
```
git config user.email "USERNAME@example.com"
```
#### Adding a Sign-off to a Commit Already Submitted/Pushed
If you have already committed (but not yet pushed) your code that is missing the `Signed-off-by` message, you can amend your commits and push them to GitHub:
```
git commit --amend --signoff
```
If you have already pushed your changes to GitHub, you'll need to force push your branch after this with `git push -f`.


