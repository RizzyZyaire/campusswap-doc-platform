# Git 手动提交操作卡（CampusSwap）

> 用途：每次改完代码/文档，自己走一遍这五步，把成果变成 GitHub 上的一次提交记录。
> 适用仓库：`D:\DevEnv\projects\campusswap`（远端 `https://github.com/RizzyZyaire/campusswap-doc-platform`）

---

## 0. 一分钟版（照着敲）

```bash
cd /d/DevEnv/projects/campusswap        # Git Bash 写法；PowerShell 里用 cd D:\DevEnv\projects\campusswap
git status                              # ① 先看改了什么（红=未暂存，绿=已暂存）
git add .                               # ② 把全部改动放进暂存区
git status                              # ③ 再确认一遍：Changes to be committed 里是不是你想要的
git commit -m "docs(m0): 冻结需求三剑客并加入机检脚本"   # ④ 生成一次提交
git log --oneline -3                    # ⑤ 确认提交进了历史
git push                                # ⑥ 推到 GitHub
```

---

## 1. 心智模型：为什么 add 和 commit 要分两步

```
工作区（你正在编辑的文件）
   │  git add         ← 挑选"这次要提交哪些改动"
   ▼
暂存区（staging area，本次提交的快照清单）
   │  git commit      ← 把清单打包成一次不可变的历史记录
   ▼
本地仓库（.git 里的提交历史）
   │  git push        ← 把本地历史同步到 GitHub
   ▼
远端仓库（GitHub）
```

- `git add .` = 把当前目录下所有**有改动且未被忽略**的文件加入暂存区。
- 只想提交一个文件：`git add docs/03-qa-review/verify-m0.ps1`
- 想撤销暂存：`git restore --staged <文件>`（旧写法 `git reset HEAD <文件>`）
- **`git commit` 只提交暂存区里的东西**，没 add 的改动不会进这次提交。

---

## 2. 提交前必查（老师红线，别把产物传上去）

```bash
# Git Bash
git ls-files | grep -E "target/|node_modules/|logs/|uploads/|dist/"

# PowerShell
git ls-files | findstr /R "target/ node_modules/ logs/ uploads/ dist/"
```

**期望输出**：只有一行 `backend/uploads/.gitkeep`（占位文件，必须有，用来让 uploads 目录存在于仓库里）。
如果出现 `target/classes/...`、`node_modules/...`、`dist/...` 之类，说明有产物被跟踪，执行：

```bash
git rm -r --cached backend/target       # 从 Git 里移除跟踪，本地文件保留
```

再顺手确认没有明文密码：

```bash
grep -rn "password" backend/src/main/resources/ | grep -v '${'
```

---

## 3. 提交信息规范（语义化前缀）

格式：`type(scope): 一句话说明`

| type | 用在什么时候 |
|---|---|
| `feat` | 新增功能 |
| `fix` | 修 bug |
| `docs` | 只改文档 |
| `chore` | 构建、配置、依赖、目录整理 |
| `refactor` | 重构（行为不变） |
| `test` | 补测试 |

**本次 M0 用**：`docs(m0): 冻结需求三剑客并加入机检脚本`

需要写正文（超过一行）时：

```bash
git commit -m "feat(doc): 文档创建接口" -m "1) 新增 POST /api/documents；2) 标题校验 1-128 字；3) 关联 US-02。"
```

---

## 4. 不想敲命令：IDEA 里怎么做

1. 在 IDEA 里按 **Ctrl+K**（Commit 窗口）
2. 左侧勾选要提交的文件（默认全勾，**取消勾选不想提交的**）
3. 下方文本框填 Message：`docs(m0): 冻结需求三剑客并加入机检脚本`
4. 点 **Commit** 按钮（**只提交到本地**）
5. 再按 **Ctrl+Shift+K**（Push 窗口）→ 点 **Push**（推到 GitHub）
6. 也可以用 Commit 按钮旁边的下拉 → **Commit and Push…**，一步干完两件事

> 注意：IDEA 的 Commit 窗口默认只显示**已跟踪文件的改动**，新文件要手动勾上（会显示为 Unversioned Files）。

---

## 5. 出错了怎么救（撤销手册）

| 情况 | 命令 | 说明 |
|---|---|---|
| add 错了文件，还没 commit | `git restore --staged <文件>` | 只移出暂存区，改动还在 |
| 某文件改坏了，想回到上次提交 | `git restore <文件>` | ⚠️ **丢弃本地修改，不可恢复** |
| commit 信息写错 | `git commit --amend -m "新信息"` | 只改最后一次提交，未 push 时安全 |
| 提交时漏了文件 | `git add 漏掉的文件` → `git commit --amend --no-edit` | 合并进上一次提交 |
| 想撤销最后一次 commit，改动保留 | `git reset --soft HEAD~1` | 回到"改完了但没提交"的状态 |
| 已经 push 才发现错 | `git commit --amend` → `git push --force-with-lease` | ⚠️ 仅在个人仓库使用（本仓库是个人项目，可用） |

**安全网**：`git reflog` 记录所有 HEAD 移动，误操作后可以用它找回提交哈希。

---

## 6. push 失败排查（按报错对号入座）

| 报错 | 原因 | 处理 |
|---|---|---|
| `Failed to connect to github.com port 443` / `Connection timed out` | 网络不通（加速器关着） | 开加速器（Clash Verge）后重试；或临时走代理：`git config --global http.proxy http://127.0.0.1:7897`，用完 `git config --global --unset http.proxy` 关掉 |
| `Authentication failed` / 反复弹登录窗 | 凭据过期 | Windows「凭据管理器 → Windows 凭据」删掉 `git:https://github.com` → 再 push，浏览器里重新授权（本机 Git Credential Manager 已装好，授权一次就长期有效） |
| `rejected ... fetch first` | 远端有你没有的提交 | `git pull --rebase` 解决冲突后再 `git push` |
| `nothing to commit` | 没有改动或忘了 add | `git status` 确认 |

**只探测不推送**（判断网络是否通）：

```bash
git ls-remote --heads origin
```

有输出（形如 `<哈希>  refs/heads/main`）= 网络通、凭据有效，直接 push 即可。

---

## 7. 本次 M0 提交的预期结果

- **新增 5 个文件**：`docs/01-requirements/USER_STORIES.md`、`PRD.md`、`GLOSSARY.md`、`docs/03-qa-review/verify-m0.ps1`、`docs/03-qa-review/GIT-CHEATSHEET.md`
- **修改 1 个文件**：`docs/MASTER-PLAN.md`（M0 任务打勾 + 交叉引用修正 + 收口记录）
- commit 后 `git log --oneline -2` 应看到：新的一行 + 旧的 `70d47e1 chore: 初始化 CampusSwap 文档管理平台`
- push 后打开 GitHub 仓库 → Commits 页面能看到同一条记录

---

## 8. 日常节奏（建议固化成习惯）

```bash
# 1. 开工前先同步，避免冲突
git pull --rebase

# 2. 干完一件事（**不是干完一天**）就提交一次
git status
git add .
git commit -m "feat(doc): 文档创建接口"

# 3. 推上去
git push
```

**提交粒度原则**：一次提交 = 一件能说清楚的事。宁可一天提交 5 次，不要攒一周提交 1 次。
