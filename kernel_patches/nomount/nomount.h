#ifndef _LINUX_NOMOUNT_H
#define _LINUX_NOMOUNT_H

#include <linux/types.h>
#include <linux/idr.h>
#include <linux/list.h>
#include <linux/hashtable.h>
#include <linux/atomic.h>
#include <linux/file.h>
#include <net/sock.h>
#include <net/genetlink.h>
#include <linux/version.h>
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 12, 0)
#include <linux/unaligned.h>
#else
#include <asm/unaligned.h>
#endif
#include <linux/jump_label.h>

#define NM_MODULE_VERSION "1.34.6"

#define NOMOUNT_VERSION    34
#define NOMOUNT_HASH_BITS  12
#define NM_FLAG_IS_DIR      (1 << 0)
#define NM_FLAG_VIRTUAL_DIR (1 << 1)
#define NM_FLAG_WHITEOUT    (1 << 2)
#define NM_FLAG_HAVE_TIMES  (1 << 3)
#define NM_FLAG_OVL_INO     (1 << 4)
#define NM_FLAG_SHADOWS_STOCK (1 << 5)
#define NM_FLAG_PUBLIC      (1 << 6)
#define NM_FLAG_STOCK_ONLY  (1 << 7)
#define NM_FLAG_HAVE_VOWN   (1 << 8)
#define NM_FLAGS_USER_MASK  (NM_FLAG_WHITEOUT | NM_FLAG_PUBLIC)
#define NM_CTX_MAX          96

#if LINUX_VERSION_CODE < KERNEL_VERSION(4, 12, 0)
#define kvzalloc(size, flags) kzalloc((size), (flags))
#endif
#if LINUX_VERSION_CODE < KERNEL_VERSION(4, 18, 0)
#define kvcalloc(n, size, flags) kcalloc((n), (size), (flags))
#endif

#define NM_BTIME_DECL(x) struct timespec64 x __maybe_unused = {0}
#if LINUX_VERSION_CODE >= KERNEL_VERSION(4, 11, 0)
#define NM_BTIME_COPY(d, s) ((d) = (s))
#else
#define NM_BTIME_COPY(d, s) ((void)0)
#endif

#define NM_CAP_FSYNC        (1 << 0)
#define NM_CAP_ODIRECT      (1 << 1)
#define NM_CAP_THPMAP       (1 << 2)
#define NM_CAP_KNOWN        (1 << 7)

#define NM_LOG_TAG "NoMount: "

#ifdef NOMOUNT_DEBUG
#define nm_debug(fmt, ...) printk(KERN_DEBUG NM_LOG_TAG "[DEBUG] " fmt, ##__VA_ARGS__)
#define nm_info(fmt, ...)  printk(KERN_INFO NM_LOG_TAG fmt, ##__VA_ARGS__)
#else
#define nm_debug(fmt, ...) no_printk(NM_LOG_TAG "[DEBUG] " fmt, ##__VA_ARGS__)
#define nm_info(fmt, ...)  no_printk(NM_LOG_TAG fmt, ##__VA_ARGS__)
#endif
#define nm_warn(fmt, ...) printk(KERN_WARNING NM_LOG_TAG "[WARN] " fmt, ##__VA_ARGS__)
#define nm_err(fmt, ...)  printk(KERN_ERR NM_LOG_TAG "[ERROR] " fmt, ##__VA_ARGS__)
#define nm_warn_once(fmt, ...) printk_once(KERN_WARNING NM_LOG_TAG "[WARN] " fmt, ##__VA_ARGS__)

static DEFINE_HASHTABLE(nomount_rules_ht, NOMOUNT_HASH_BITS);
static LIST_HEAD(nomount_sb_list);
static DEFINE_IDR(nomount_uid_idr);
static DEFINE_MUTEX(nomount_write_mutex);

#define nm_get_vpath(rule) ((rule)->paths)
#define nm_get_rpath(rule) ((rule)->paths + (rule)->v_len + 1)

struct nm_iop {
    struct inode_operations fake_iop;
    const struct inode_operations *orig_iop;
    struct nomount_dir_node *dir_node;
};

struct nm_fop {
    struct file_operations fake_fop;
    const struct file_operations *orig_fop;
    struct nomount_dir_node *dir_node;
};

struct nm_sop {
    struct super_operations fake_sop;
    const struct super_operations *orig_sop;
    const struct xattr_handler **orig_xattr;
    const struct xattr_handler **fake_xattr;
    struct super_block *sb;
    struct rcu_head rcu;
    struct list_head list;
};

struct nm_dsnap;

struct nm_inode_info {
    struct path r_path;
    struct path s_path;
    struct nomount_dir_node *dir_node;
    char v_ctx[NM_CTX_MAX];
    u16 v_ctx_len;
    unsigned long v_ino;
    u64 v_dino, v_pdino;
    dev_t v_dev, v_mapdev;
    struct timespec64 v_atime, v_mtime, v_ctime;
    struct timespec64 v_btime;
    u64 v_attributes, v_attr_mask;
    u32 v_blksize;
    u16 v_cratio;
    u32 v_result_mask;
    u32 v_dio_mem, v_dio_off;
    u8  v_cap;
    kuid_t v_uid;
    kgid_t v_gid;
    umode_t v_mode;
    u16 flags;
    u32 gen;
    struct nm_dsnap *dsnap;
    spinlock_t dsnap_lock;
    struct rcu_head rcu;
};

#define nm_get_real_inode(v_inode) \
    (((v_inode)->i_private && ((struct nm_inode_info *)(v_inode)->i_private)->r_path.dentry) ? \
        d_backing_inode(((struct nm_inode_info *)(v_inode)->i_private)->r_path.dentry) : NULL)

#define NM_CHILD_HT_BITS 5

struct nomount_child_node {
    struct rcu_head rcu;
    struct hlist_node hnode;
    u32 name_hash;
    u64 fake_ino;
    int id;
    u8 d_type;
    u16 flags;
    u16 name_len;
    struct nomount_rule *rule;

    char name[]; 
};

struct nomount_dir_node {
    struct idr children_idr;
    DECLARE_HASHTABLE(children_ht, NM_CHILD_HT_BITS);
    loff_t real_eof;
    loff_t max_real_pos;
    u64 bloom_mask;
    bool has_public;
    atomic_t refcount;
    struct rcu_head rcu;
    union {
        struct inode *dir_inode;
        struct nomount_rule *owner_rule;
        unsigned long _tag_ptr;
    };
};

struct nomount_rule {
    struct hlist_node vpath_node;
    struct hlist_node victim_node;
    struct nomount_dir_node *parent_dir;
    struct nomount_dir_node *this_dir;
    struct path r_path;
    struct path s_path;
    unsigned long v_ino;
    u64 v_dino, v_pdino;
    dev_t v_dev;
    dev_t v_mapdev;
    struct timespec64 v_atime, v_mtime, v_ctime;
    struct timespec64 v_btime;
    u64 v_attributes, v_attr_mask;
    u32 v_blksize;
    u16 v_cratio;
    u32 v_result_mask;
    u32 v_dio_mem, v_dio_off;
    u8  v_cap;
    kuid_t v_uid;
    kgid_t v_gid;
    umode_t v_mode;
    char v_ctx[NM_CTX_MAX];
    u16 v_ctx_len;
    u32 v_hash;
    u16 v_len;
    u16 flags;
    unsigned int target_uid;

    char paths[]; 
};

#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 16, 0)
static const struct file_operations nm_file_fops_mmap_prepare;
static const struct file_operations nm_file_fops_mmap_prepare_thp;
#endif
static const struct file_operations nm_file_fops;
static const struct file_operations nm_file_fops_thp;
static const struct inode_operations nm_file_iops;
static const struct file_operations nm_dir_fops;
static const struct inode_operations nm_dir_iops;
static const struct dentry_operations nm_dops;

static int nomount_generate_virtual_topology(struct nomount_rule *target_rule);
static struct nomount_rule *nm_alloc_rule(const char *v_path, const char *r_path, u16 v_len, u16 r_len, u32 flags, unsigned int target_uid);
static void nm_free_rule(struct nomount_rule *rule);
static void nm_detach_rule_locked(struct nomount_rule *rule, struct hlist_head *victims, bool prune);
struct nm_rule_info {
    u32 flags;
    struct path s_path;
    unsigned long v_ino;
    u64 v_dino, v_pdino;
    dev_t v_dev, v_mapdev;
    struct timespec64 v_atime, v_mtime, v_ctime;
    struct timespec64 v_btime;
    u64 v_attributes, v_attr_mask;
    u32 v_blksize;
    u16 v_cratio;
    u32 v_result_mask;
    u32 v_dio_mem, v_dio_off;
    u8  v_cap;
    kuid_t v_uid;
    kgid_t v_gid;
    umode_t v_mode;
    char v_ctx[NM_CTX_MAX];
    u16 v_ctx_len;
    struct path r_path;
    struct nomount_dir_node *this_dir;
    u32 gen;
};

static struct inode *nomount_create_new_inode(struct super_block *virtual_sb, struct nm_rule_info *rule_info);
void vfs_map_meta_override(const struct inode *inode, dev_t *dev,
				 unsigned long *ino);

#define NM_POS_HEADROOM 65536

static inline bool nm_is_virtual_pos(const struct nomount_dir_node *d, loff_t pos)
{
    loff_t eof, mx;

    if (!d) return false;
    eof = READ_ONCE(d->real_eof);
    mx  = READ_ONCE(d->max_real_pos);
    return eof && pos > eof && pos > mx && pos <= eof + NM_POS_HEADROOM;
}

static inline loff_t nm_pack_pos(const struct nomount_dir_node *d, int id)
{
    return READ_ONCE(d->real_eof) + 1 + id;
}

static inline int nm_unpack_pos(const struct nomount_dir_node *d, loff_t pos)
{
    loff_t id = pos - READ_ONCE(d->real_eof) - 1;

    if (id < 0 || id > NM_POS_HEADROOM) return -1;
    return (int)id;
}

static inline void nm_note_real_pos(struct nomount_dir_node *d, loff_t pos)
{
    if (!d || pos <= 0 || pos > (loff_t)(S64_MAX - NM_POS_HEADROOM)) return;
    if (pos > READ_ONCE(d->max_real_pos)) WRITE_ONCE(d->max_real_pos, pos);
}

static inline void nm_publish_real_eof(struct nomount_dir_node *d, loff_t eof_hint)
{
    loff_t base;

    if (!d) return;
    base = READ_ONCE(d->max_real_pos);
    if (eof_hint > 0 && eof_hint <= (loff_t)(S64_MAX - NM_POS_HEADROOM) && eof_hint > base)
        base = eof_hint;
    if (!base) base = 2;
    WRITE_ONCE(d->real_eof, base);
}

#ifndef NOMOUNT_NL_PROTO
#define NOMOUNT_NL_PROTO 29
#endif

#define NM_CMD_TO_TYPE(c) (NLMSG_MIN_TYPE + (c))
#define NM_TYPE_TO_CMD(t) ((int)(t) - NLMSG_MIN_TYPE)

enum {
    NM_CMD_UNSPEC = 0,
    NM_CMD_GET_VERSION,
    NM_CMD_ADD_RULE,
    NM_CMD_DEL_RULE,
    NM_CMD_CLEAR_ALL,
    NM_CMD_ADD_UID,
    NM_CMD_DEL_UID,
    NM_CMD_GET_LIST,
    NM_CMD_GET_UIDS,
    NM_CMD_SET_KNOB,
    NM_CMD_GET_PATHHIDE,
    NM_CMD_GET_GHOST,
    __NM_CMD_MAX,
};

enum {
    NM_KNOB_RESERVED_0 = 0,
    NM_KNOB_RESERVED_1,
    NM_KNOB_RESERVED_2,
    NM_KNOB_RESERVED_3,
    NM_KNOB_VDIR_EROFS_SIZE,
    NM_KNOB_HIDE_ISOLATED,
    NM_KNOB_PATHHIDE,
    NM_KNOB_GHOST,
    __NM_KNOB_MAX,
};

enum {
    NOMOUNT_ATTR_UNSPEC = 0,
    NOMOUNT_ATTR_VIRTUAL_PATH,
    NOMOUNT_ATTR_REAL_PATH,
    NOMOUNT_ATTR_FLAGS,
    NOMOUNT_ATTR_UID,
    NOMOUNT_ATTR_VERSION,
    NOMOUNT_ATTR_PAYLOAD,
    NOMOUNT_ATTR_MODVER,
    __NOMOUNT_ATTR_MAX,
};

static const struct nla_policy nomount_genl_policy[__NOMOUNT_ATTR_MAX];

#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 2, 0)
    #define NM_NLMSG_PARSE(nlh, tb) \
        nlmsg_parse_deprecated((nlh), 0, (tb), __NOMOUNT_ATTR_MAX - 1, nomount_genl_policy, NULL)
#elif LINUX_VERSION_CODE >= KERNEL_VERSION(4, 12, 0)
    #define NM_NLMSG_PARSE(nlh, tb) \
        nlmsg_parse((nlh), 0, (tb), __NOMOUNT_ATTR_MAX - 1, nomount_genl_policy, NULL)
#else
    #define NM_NLMSG_PARSE(nlh, tb) \
        nlmsg_parse((nlh), 0, (tb), __NOMOUNT_ATTR_MAX - 1, nomount_genl_policy)
#endif

#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 3, 0)
    #define IDMAP_PATH(path) mnt_idmap((path).mnt),
    #define IDMAP_ARG struct mnt_idmap *idmap,
    #define IDMAP_CALL idmap,
#elif LINUX_VERSION_CODE >= KERNEL_VERSION(5, 12, 0)
    #define IDMAP_PATH(path) mnt_user_ns((path).mnt),
    #define IDMAP_ARG struct user_namespace *mnt_userns,
    #define IDMAP_CALL mnt_userns,
#else
    #define IDMAP_PATH(path)
    #define IDMAP_ARG
    #define IDMAP_CALL
#endif

#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 1, 0)
    #define NM_ACTOR_RET bool
    #define NM_ACTOR_CONTINUE true
#else
    #define NM_ACTOR_RET int
    #define NM_ACTOR_CONTINUE 0
#endif

#if LINUX_VERSION_CODE < KERNEL_VERSION(5, 12, 0) && LINUX_VERSION_CODE >= KERNEL_VERSION(5, 2, 0)
    #define FLAGS_ARG , int flags
    #define FLAGS_VAL , flags
#else
    #define FLAGS_ARG
    #define FLAGS_VAL
#endif

static inline void nm_sync_inode_times(struct inode *v_inode, struct inode *r_inode)
{
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 12, 0)
    v_inode->i_atime_sec = r_inode->i_atime_sec;
    v_inode->i_atime_nsec = r_inode->i_atime_nsec;
    v_inode->i_mtime_sec = r_inode->i_mtime_sec;
    v_inode->i_mtime_nsec = r_inode->i_mtime_nsec;
    v_inode->i_ctime_sec = r_inode->i_ctime_sec;
    v_inode->i_ctime_nsec = r_inode->i_ctime_nsec;
#elif LINUX_VERSION_CODE >= KERNEL_VERSION(6, 7, 0)
    inode_set_atime_to_ts(v_inode, inode_get_atime(r_inode));
    inode_set_mtime_to_ts(v_inode, inode_get_mtime(r_inode));
    inode_set_ctime_to_ts(v_inode, inode_get_ctime(r_inode));
#elif LINUX_VERSION_CODE >= KERNEL_VERSION(6, 6, 0)
    v_inode->i_atime = r_inode->i_atime;
    v_inode->i_mtime = r_inode->i_mtime;
    inode_set_ctime_to_ts(v_inode, inode_get_ctime(r_inode));
#else
    v_inode->i_atime = r_inode->i_atime;
    v_inode->i_mtime = r_inode->i_mtime;
    v_inode->i_ctime = r_inode->i_ctime;
#endif
}

static inline struct timespec64 nm_inode_mtime(struct inode *inode)
{
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 7, 0)
    return inode_get_mtime(inode);
#else
    return inode->i_mtime;
#endif
}

static inline int nm_call_iterate(struct file *file, struct dir_context *ctx, const struct file_operations *fop)
{
    if (fop->iterate_shared)
        return fop->iterate_shared(file, ctx);
#if LINUX_VERSION_CODE < KERNEL_VERSION(6, 5, 0)
    else if (fop->iterate)
        return fop->iterate(file, ctx);
#endif
    return -ENOTDIR;
}

#if LINUX_VERSION_CODE < KERNEL_VERSION(6, 5, 0)
#define nm_get_fop(p) ({                                                        \
    const struct file_operations *__nf = (p);                                   \
    struct nm_fop *__nr = __get_nm(__nf, struct nm_fop, fake_fop,               \
                                   iterate_shared, nomount_hijacked_iterate_dir);\
    if (!__nr)                                                                  \
        __nr = __get_nm(__nf, struct nm_fop, fake_fop,                          \
                        iterate, nomount_hijacked_iterate_dir);                 \
    __nr; })
#else
#define nm_get_fop(p) \
    __get_nm((p), struct nm_fop, fake_fop, iterate_shared, nomount_hijacked_iterate_dir)
#endif

static inline void nm_install_dentry_ops(struct dentry *dentry)
{
    dentry->d_flags &= ~(DCACHE_OP_HASH | DCACHE_OP_COMPARE |
                         DCACHE_OP_REVALIDATE | DCACHE_OP_WEAK_REVALIDATE |
                         DCACHE_OP_DELETE | DCACHE_OP_PRUNE | DCACHE_OP_REAL);
    dentry->d_op = &nm_dops;
    dentry->d_flags |= DCACHE_OP_REVALIDATE;
}

static inline void nm_tag_passthrough_dentry(struct dentry *dentry)
{
#ifdef DCACHE_DONTCACHE
    dentry->d_flags |= DCACHE_DONTCACHE;
#endif
    if (!dentry->d_op)
        nm_install_dentry_ops(dentry);
}

#endif
