const REMOTE_COMMANDS = new Set([
  'apps',
  'open',
  'lock',
  'say',
  'sayurdu',
  'playaudio',
  'stopaudio',
  'pauseaudio',
  'resumeaudio',
  'audiostatus',
  'parentpin',
  'shield',
  'screenshot',
  'files',
  'filestat',
  'mkdir',
  'rename',
  'move',
  'delete',
  'uploadfile',
  'readtext',
  'download',
  'volume',
  'info',
  'permstatus',
  'location',
  'camerasnap',
  'contactlookup',
  'smsdraft',
  'fileshareintent',
  'quicklaunch',
  'torchpattern',
  'ringtoneprofile',
  'screentimeoutset',
  'mediacontrol',
  'randomquote',
  'fakecallui',
  'shakealert',
  'show',
  'message',
  'lockapp',
  'unlockapp',
  'lockedapps',
  'usage',
]);

const ADMIN_COMMANDS = new Set(['pair', 'bind', 'unbind', 'admins', 'devices']);

// Tokenizer supports quoted arguments for message and say commands.
function tokenize(text) {
  const regex = /"([^"]+)"|'([^']+)'|(\S+)/g;
  const tokens = [];
  let match;
  while ((match = regex.exec(text)) !== null) {
    tokens.push(match[1] || match[2] || match[3]);
  }
  return tokens;
}

function parseCommandInput(content, prefix) {
  if (!content || !content.startsWith(prefix)) {
    return null;
  }

  const raw = content.slice(prefix.length).trim();
  if (!raw) {
    return null;
  }

  const tokens = tokenize(raw);
  if (tokens.length === 0) {
    return null;
  }

  const name = tokens[0].toLowerCase();
  const args = tokens.slice(1);

  return {
    name,
    args,
    isRemote: REMOTE_COMMANDS.has(name),
    isAdmin: ADMIN_COMMANDS.has(name),
  };
}

function buildRemotePayload(name, args, attachment, maxImageBytes) {
  switch (name) {
    case 'apps':
    case 'lock':
    case 'stopaudio':
    case 'pauseaudio':
    case 'resumeaudio':
    case 'audiostatus':
    case 'screenshot':
    case 'files':
    case 'camerasnap':
    case 'randomquote':
    case 'info':
    case 'permstatus':
    case 'location':
    case 'lockedapps':
    case 'usage':
      return { payload: {} };
    case 'open':
      if (!args[0]) {
        return { error: 'Usage: !open <app package or display name>' };
      }
      return { payload: { target: args.join(' ') } };
    case 'say':
      if (args.length === 0) {
        return { error: 'Usage: !say <text>' };
      }
      return { payload: { text: args.join(' ') } };
    case 'sayurdu':
      if (args.length === 0) {
        return { error: 'Usage: !sayurdu <urdu text>' };
      }
      return { payload: { text: args.join(' ') } };
    case 'playaudio': {
      if (!args[0]) {
        return { error: 'Usage: !playaudio <url> [repeat]' };
      }
      let repeat = 1;
      if (args[1] !== undefined) {
        repeat = Number(args[1]);
      }
      if (!Number.isFinite(repeat) || repeat < 1 || repeat > 100) {
        return { error: 'Usage: !playaudio <url> [repeat]' };
      }
      return { payload: { url: args[0], repeat: Math.round(repeat) } };
    }
    case 'parentpin':
      if (!args[0]) {
        return { error: 'Usage: !parentpin <4-12 digit pin>' };
      }
      return { payload: { pin: args[0] } };
    case 'shield': {
      const action = (args[0] || 'status').toLowerCase();
      if (!['enable', 'disable', 'status', 'relock'].includes(action)) {
        return { error: 'Usage: !shield <enable|disable|status|relock>' };
      }
      return { payload: { action } };
    }
    case 'download':
      if (!args[0]) {
        return { error: 'Usage: !download <path>' };
      }
      return { payload: { path: args.join(' ') } };
    case 'filestat':
      if (!args[0]) {
        return { error: 'Usage: !filestat <path>' };
      }
      return { payload: { path: args.join(' ') } };
    case 'mkdir':
      if (!args[0]) {
        return { error: 'Usage: !mkdir <path>' };
      }
      return { payload: { path: args.join(' ') } };
    case 'rename':
      if (!args[0] || !args[1]) {
        return { error: 'Usage: !rename <path> <new_name>' };
      }
      return { payload: { path: args[0], new_name: args[1] } };
    case 'move':
      if (!args[0] || !args[1]) {
        return { error: 'Usage: !move <source> <target_dir>' };
      }
      return { payload: { source: args[0], target_dir: args[1] } };
    case 'delete':
      if (!args[0]) {
        return { error: 'Usage: !delete <path> [recursive:true|false]' };
      }
      return { payload: { path: args[0], recursive: args[1] === 'true' } };
    case 'uploadfile':
      if (!args[0] || !args[1]) {
        return { error: 'Usage: !uploadfile <target_dir> <url> [file_name]' };
      }
      return { payload: { target_dir: args[0], url: args[1], file_name: args[2] } };
    case 'readtext': {
      if (!args[0]) {
        return { error: 'Usage: !readtext <path> [max_chars]' };
      }
      let maxChars = 2000;
      if (args[1] !== undefined) {
        maxChars = Number(args[1]);
      }
      if (!Number.isFinite(maxChars) || maxChars < 1) {
        return { error: 'Usage: !readtext <path> [max_chars]' };
      }
      return { payload: { path: args[0], max_chars: Math.round(maxChars) } };
    }
    case 'volume': {
      const value = Number(args[0]);
      if (!Number.isFinite(value) || value < 0 || value > 100) {
        return { error: 'Usage: !volume <0-100>' };
      }
      return { payload: { value: Math.round(value) } };
    }
    case 'show': {
      const seconds = Number(args[0]);
      if (!Number.isFinite(seconds) || seconds < 1 || seconds > 60) {
        return { error: 'Usage: !show <1-60> with an image attachment' };
      }

      if (!attachment) {
        return { error: 'Attach a single image to use !show' };
      }

      const isImage = (attachment.contentType || '').startsWith('image/');
      if (!isImage) {
        return { error: 'Attachment must be an image' };
      }

      if (attachment.size && attachment.size > maxImageBytes) {
        return { error: `Attachment too large; max ${Math.floor(maxImageBytes / (1024 * 1024))} MB` };
      }

      return {
        payload: {
          seconds: Math.round(seconds),
          imageUrl: attachment.url,
          imageName: attachment.name || 'image',
          imageContentType: attachment.contentType || 'image/*',
        },
      };
    }
    case 'message':
      if (args.length === 0) {
        return { error: 'Usage: !message <text>' };
      }
      return { payload: { text: args.join(' ') } };
    case 'contactlookup': {
      if (!args[0]) {
        return { error: 'Usage: !contactlookup <query> [limit]' };
      }
      const payload = { query: args[0] };
      if (args[1] !== undefined) {
        const limit = Number(args[1]);
        if (!Number.isFinite(limit) || limit < 1) {
          return { error: 'Usage: !contactlookup <query> [limit]' };
        }
        payload.limit = Math.round(limit);
      }
      return { payload };
    }
    case 'smsdraft':
      if (!args[0] || args.length < 2) {
        return { error: 'Usage: !smsdraft <number> <message>' };
      }
      return { payload: { number: args[0], message: args.slice(1).join(' ') } };
    case 'fileshareintent':
      if (!args[0]) {
        return { error: 'Usage: !fileshareintent <path> [mime_type]' };
      }
      return { payload: { path: args[0], mimeType: args[1] } };
    case 'quicklaunch':
      if (!args[0]) {
        return { error: 'Usage: !quicklaunch <package_or_url>' };
      }
      if (args[0].startsWith('http://') || args[0].startsWith('https://')) {
        return { payload: { url: args[0] } };
      }
      return { payload: { packageName: args[0] } };
    case 'torchpattern': {
      const repeats = args[0] === undefined ? 3 : Number(args[0]);
      const onMs = args[1] === undefined ? 250 : Number(args[1]);
      const offMs = args[2] === undefined ? 250 : Number(args[2]);
      if (!Number.isFinite(repeats) || !Number.isFinite(onMs) || !Number.isFinite(offMs)) {
        return { error: 'Usage: !torchpattern [repeats] [on_ms] [off_ms]' };
      }
      return { payload: { repeats: Math.round(repeats), on_ms: Math.round(onMs), off_ms: Math.round(offMs) } };
    }
    case 'ringtoneprofile':
      if (!['normal', 'vibrate', 'silent'].includes((args[0] || '').toLowerCase())) {
        return { error: 'Usage: !ringtoneprofile <normal|vibrate|silent>' };
      }
      return { payload: { mode: args[0].toLowerCase() } };
    case 'screentimeoutset': {
      const seconds = Number(args[0]);
      if (!Number.isFinite(seconds) || seconds < 1) {
        return { error: 'Usage: !screentimeoutset <seconds>' };
      }
      return { payload: { seconds: Math.round(seconds) } };
    }
    case 'mediacontrol':
      if (!['play', 'pause', 'next', 'previous', 'stop', 'toggle'].includes((args[0] || '').toLowerCase())) {
        return { error: 'Usage: !mediacontrol <play|pause|next|previous|stop|toggle>' };
      }
      return { payload: { action: args[0].toLowerCase() } };
    case 'fakecallui':
      return { payload: { callerName: args.join(' ') || 'Unknown Caller' } };
    case 'shakealert': {
      const action = (args[0] || 'status').toLowerCase();
      if (!['start', 'stop', 'status'].includes(action)) {
        return { error: 'Usage: !shakealert <start|stop|status>' };
      }
      return { payload: { action } };
    }
    case 'lockapp':
      if (!args[0]) {
        return { error: 'Usage: !lockapp <package>' };
      }
      return { payload: { packageName: args[0] } };
    case 'unlockapp':
      if (!args[0]) {
        return { error: 'Usage: !unlockapp <package>' };
      }
      return { payload: { packageName: args[0] } };
    default:
      return { error: 'Unknown remote command' };
  }
}

module.exports = {
  REMOTE_COMMANDS,
  ADMIN_COMMANDS,
  parseCommandInput,
  buildRemotePayload,
};
