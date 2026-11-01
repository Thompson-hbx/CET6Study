use serde::{Deserialize, Serialize};
use std::{
    collections::HashSet,
    fs::{self, File, OpenOptions},
    io::Read,
    path::PathBuf,
    sync::Mutex,
};

const MAX_BYTES: u64 = 32 * 1024 * 1024;

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Draft {
    pub id: String,
    pub title: String,
    pub prompt: String,
    pub text: String,
    pub review_text: String,
    pub review_source: String,
    pub accepted: Vec<usize>,
    pub dismissed: Vec<usize>,
    pub parent_id: Option<String>,
    pub updated_at: u64,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Workspace {
    pub version: u32,
    pub revision: u64,
    pub active_id: Option<String>,
    pub drafts: Vec<Draft>,
}

impl Workspace {
    fn empty() -> Self {
        Self {
            version: 1,
            revision: 0,
            active_id: None,
            drafts: vec![],
        }
    }
    fn validate(&self) -> Result<(), String> {
        if self.version != 1 || self.drafts.len() > 100 {
            return Err("草稿文件版本或数量无效".into());
        }
        let mut ids = HashSet::new();
        for draft in &self.drafts {
            if draft.id.is_empty()
                || draft.id.len() > 64
                || !draft
                    .id
                    .bytes()
                    .all(|c| c.is_ascii_alphanumeric() || c == b'-')
                || !ids.insert(&draft.id)
            {
                return Err("草稿标识无效或重复".into());
            }
            if draft.title.chars().count() > 1000
                || draft.prompt.chars().count() > 60000
                || draft.text.chars().count() > 30000
                || draft.review_source.chars().count() > 30000
                || draft.review_text.chars().count() > 200000
            {
                return Err("草稿内容过长，请拆分保存".into());
            }
            let accepted: HashSet<_> = draft.accepted.iter().collect();
            let dismissed: HashSet<_> = draft.dismissed.iter().collect();
            if accepted.len() != draft.accepted.len()
                || dismissed.len() != draft.dismissed.len()
                || !accepted.is_disjoint(&dismissed)
                || draft
                    .accepted
                    .iter()
                    .chain(&draft.dismissed)
                    .any(|id| *id > 1000)
            {
                return Err("批注状态无效".into());
            }
            if draft
                .parent_id
                .as_ref()
                .is_some_and(|id| id.len() > 64 || id == &draft.id)
            {
                return Err("修改稿来源无效".into());
            }
        }
        if self.active_id.as_ref().is_some_and(|id| !ids.contains(id)) {
            return Err("所选草稿不存在".into());
        }
        Ok(())
    }
}

pub struct WorkspaceStore {
    directory: PathBuf,
    workspace: Mutex<Workspace>,
    _lock: File,
}
impl WorkspaceStore {
    pub fn open(directory: PathBuf) -> Result<Self, String> {
        fs::create_dir_all(&directory).map_err(|_| "无法创建原型草稿目录")?;
        let mut options = OpenOptions::new();
        options.read(true).write(true).create(true);
        #[cfg(windows)]
        {
            use std::os::windows::fs::OpenOptionsExt;
            options.share_mode(0);
        }
        let lock = options
            .open(directory.join("workspace.lock"))
            .map_err(|_| "草稿目录已被另一窗口使用或无法访问")?;
        let workspace = match File::open(directory.join("workspace.json")) {
            Ok(file) => {
                let mut bytes = vec![];
                file.take(MAX_BYTES + 1)
                    .read_to_end(&mut bytes)
                    .map_err(|_| "草稿文件无法读取，原文件已保留")?;
                if bytes.len() as u64 > MAX_BYTES {
                    return Err("草稿文件过大，原文件已保留".into());
                }
                let saved: Workspace = serde_json::from_slice(&bytes)
                    .map_err(|_| "草稿文件损坏或版本不兼容，原文件已保留")?;
                saved.validate()?;
                saved
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Workspace::empty(),
            Err(_) => return Err("草稿文件无法打开，原文件已保留".into()),
        };
        Ok(Self {
            directory,
            workspace: Mutex::new(workspace),
            _lock: lock,
        })
    }
    pub fn load(&self) -> Result<Workspace, String> {
        Ok(self
            .workspace
            .lock()
            .map_err(|_| "草稿状态不可用，请重新打开应用")?
            .clone())
    }
    pub fn save(&self, incoming: Workspace) -> Result<Workspace, String> {
        incoming.validate()?;
        let mut current = self
            .workspace
            .lock()
            .map_err(|_| "草稿状态不可用，请重新打开应用")?;
        if incoming.revision != current.revision {
            return Err("草稿版本已变化，已停止覆盖；请重新打开后核对".into());
        }
        let mut saved = incoming;
        saved.revision = saved.revision.checked_add(1).ok_or("草稿版本超过上限")?;
        let bytes = serde_json::to_vec(&saved).map_err(|_| "草稿无法序列化")?;
        if bytes.len() as u64 > MAX_BYTES {
            return Err("草稿总量超过 32 MiB".into());
        }
        let temporary = self.directory.join("workspace.tmp");
        use std::io::Write;
        let mut file =
            File::create(&temporary).map_err(|_| "无法写入草稿，当前编辑仍保留在窗口中")?;
        file.write_all(&bytes)
            .and_then(|_| file.sync_all())
            .map_err(|_| "草稿写入失败，当前编辑仍保留在窗口中")?;
        drop(file);
        fs::rename(temporary, self.directory.join("workspace.json"))
            .map_err(|_| "草稿替换失败，旧文件已保留")?;
        *current = saved.clone();
        Ok(saved)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    struct Folder(PathBuf);
    impl Folder {
        fn new() -> Self {
            Self(std::env::temp_dir().join(format!(
                    "zhupi-test-{}-{}",
                    std::process::id(),
                    std::time::SystemTime::now()
                        .duration_since(std::time::UNIX_EPOCH)
                        .unwrap()
                        .as_nanos()
                )))
        }
    }
    impl Drop for Folder {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }
    fn draft() -> Draft {
        Draft {
            id: "original".into(),
            title: "原创测试".into(),
            prompt: "Original prompt".into(),
            text: "A saved essay.".into(),
            review_text: String::new(),
            review_source: String::new(),
            accepted: vec![],
            dismissed: vec![],
            parent_id: None,
            updated_at: 1,
        }
    }
    #[test]
    fn save_and_reopen_preserve_draft_and_revision() {
        let folder = Folder::new();
        {
            let store = WorkspaceStore::open(folder.0.clone()).unwrap();
            let mut workspace = store.load().unwrap();
            workspace.drafts.push(draft());
            workspace.active_id = Some("original".into());
            assert_eq!(1, store.save(workspace).unwrap().revision);
        }
        assert_eq!(
            "A saved essay.",
            WorkspaceStore::open(folder.0.clone())
                .unwrap()
                .load()
                .unwrap()
                .drafts[0]
                .text
        );
    }
    #[test]
    fn stale_save_cannot_replace_newer_work() {
        let folder = Folder::new();
        let store = WorkspaceStore::open(folder.0.clone()).unwrap();
        let stale = store.load().unwrap();
        let mut fresh = stale.clone();
        fresh.drafts.push(draft());
        store.save(fresh).unwrap();
        assert!(store.save(stale).is_err());
        assert_eq!(1, store.load().unwrap().drafts.len());
    }
    #[test]
    fn corrupt_store_is_never_reset() {
        let folder = Folder::new();
        fs::create_dir_all(&folder.0).unwrap();
        fs::write(folder.0.join("workspace.json"), b"{broken").unwrap();
        assert!(WorkspaceStore::open(folder.0.clone()).is_err());
        assert_eq!(
            b"{broken",
            fs::read(folder.0.join("workspace.json"))
                .unwrap()
                .as_slice()
        );
    }
    #[test]
    fn failed_disk_write_preserves_memory_and_previous_file() {
        let folder = Folder::new();
        let store = WorkspaceStore::open(folder.0.clone()).unwrap();
        let mut initial = store.load().unwrap();
        initial.drafts.push(draft());
        let mut next = store.save(initial).unwrap();
        let original = fs::read(folder.0.join("workspace.json")).unwrap();
        fs::create_dir(folder.0.join("workspace.tmp")).unwrap();
        next.drafts[0].text = "Unsaved replacement".into();
        assert!(store.save(next).is_err());
        assert_eq!(original, fs::read(folder.0.join("workspace.json")).unwrap());
        assert_eq!("A saved essay.", store.load().unwrap().drafts[0].text);
    }
    #[test]
    fn invalid_decisions_do_not_change_saved_state() {
        let folder = Folder::new();
        let store = WorkspaceStore::open(folder.0.clone()).unwrap();
        let mut workspace = store.load().unwrap();
        let mut item = draft();
        item.accepted = vec![0];
        item.dismissed = vec![0];
        workspace.drafts.push(item);
        assert!(store.save(workspace).is_err());
        assert_eq!(0, store.load().unwrap().revision);
    }
}
